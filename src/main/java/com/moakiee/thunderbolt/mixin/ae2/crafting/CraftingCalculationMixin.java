package com.moakiee.thunderbolt.mixin.ae2.crafting;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.config.Actionable;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.NetworkCraftingSimulationState;

import com.moakiee.thunderbolt.ThunderboltCore;
import com.moakiee.thunderbolt.api.crafting.planner.CraftingInventoryView;
import com.moakiee.thunderbolt.api.crafting.planner.CraftingPlannerRequest;
import com.moakiee.thunderbolt.api.crafting.planner.CraftingPlannerStatus;
import com.moakiee.thunderbolt.mixin.ae2.crafting.support.FastCraftingControl;
import com.moakiee.thunderbolt.mixin.ae2.crafting.support.FastCraftingPlanner;
import com.moakiee.thunderbolt.mixin.ae2.crafting.support.FastPlanningWatchdog;
import com.moakiee.thunderbolt.core.crafting.planner.PlannerDispatch;
import com.moakiee.thunderbolt.core.crafting.planner.PlanningMetadataStore;
import com.moakiee.thunderbolt.core.crafting.support.CraftingStockPolicy;

/**
 * Installs the linear-time autocrafting fast path inside AE2's per-amount attempt
 * ({@code CraftingCalculation#runCraftAttempt(boolean, long)}).
 *
 * <p>By hooking the per-amount attempt instead of {@code computePlan}, AE2 keeps driving its own
 * strategy and binary-search loop (no need to reimplement CRAFT_LESS); we only replace the expensive
 * tree simulation of each attempt. The planner is best-effort and never falls back to AE2's exhaustive
 * simulator (Policy A) — that quadratic/NBT-fuzzy path is exactly what hangs on heavy graphs.
 *
 * <p>Gating: the crafting-service extension explicitly enables this optimization for a fresh
 * calculation when a host integration enables it. Product-specific plan wrapping belongs to that
 * integration's registered planner, not to this AE2 bridge.
 *
 * <p>Every attempt is wrapped by {@link FastPlanningWatchdog} so a hang is captured with a live stack.
 */
@Mixin(value = CraftingCalculation.class, remap = false)
public abstract class CraftingCalculationMixin implements FastCraftingControl {

    @Shadow
    private NetworkCraftingSimulationState networkInv;

    @Shadow
    private AEKey output;

    @Shadow
    ICraftingSimulationRequester simRequester;

    @Shadow
    private boolean simulate;

    @Shadow
    private long requestedAmount;

    @Shadow
    abstract net.minecraft.world.level.Level getLevel();

    @Unique
    private boolean thunderbolt$fastPlanningInitialized;

    @Unique
    private boolean thunderbolt$fastPlanningEnabled;

    @Unique
    @Nullable
    private CraftingPlan thunderbolt$cachedFullSimulationPlan;

    @Unique
    private long thunderbolt$calculationStartedNanos;

    @Unique
    private int thunderbolt$attempts;

    @Unique
    private int thunderbolt$fastHandledAttempts;

    @Unique
    private int thunderbolt$fastFallbackAttempts;

    @Unique
    private int thunderbolt$cachedSimulationAttempts;

    @Unique
    private int thunderbolt$fastFailures;

    @Override
    public void thunderbolt$setFastPlanningEnabled(boolean enabled) {
        this.thunderbolt$fastPlanningInitialized = true;
        this.thunderbolt$fastPlanningEnabled = enabled;
    }

    @Override
    public boolean thunderbolt$isFastPlanningEnabled() {
        return this.thunderbolt$fastPlanningInitialized && this.thunderbolt$fastPlanningEnabled;
    }

    @Inject(method = "run", at = @At("HEAD"), remap = false)
    private void thunderbolt$startCalculationTiming(CallbackInfoReturnable<ICraftingPlan> cir) {
        thunderbolt$calculationStartedNanos = System.nanoTime();
        thunderbolt$attempts = 0;
        thunderbolt$fastHandledAttempts = 0;
        thunderbolt$fastFallbackAttempts = 0;
        thunderbolt$cachedSimulationAttempts = 0;
        thunderbolt$fastFailures = 0;
        ThunderboltCore.LOGGER.info(
                "[Thunderbolt Core][crafting-timing] started: output={} requested={} fastEnabled={}",
                output, requestedAmount, thunderbolt$isFastPlanningEnabled());
    }

    @Inject(method = "run", at = @At("RETURN"), remap = false)
    private void thunderbolt$finishCalculationTiming(CallbackInfoReturnable<ICraftingPlan> cir) {
        var result = cir.getReturnValue();
        thunderbolt$clearSimulationFallback();
        long elapsedNanos = thunderbolt$calculationStartedNanos == 0L
                ? 0L
                : Math.max(0L, System.nanoTime() - thunderbolt$calculationStartedNanos);
        double wallMs = TimeUnit.NANOSECONDS.toMicros(elapsedNanos) / 1_000.0D;
        ThunderboltCore.LOGGER.info(
                "[Thunderbolt Core][crafting-timing] finished: output={} requested={} wallMs={} "
                        + "fastEnabled={} attempts={} fastHandled={} fastFallback={} cachedSimulation={} "
                        + "fastFailures={} result={}",
                output, requestedAmount, wallMs, thunderbolt$isFastPlanningEnabled(), thunderbolt$attempts,
                thunderbolt$fastHandledAttempts, thunderbolt$fastFallbackAttempts,
                thunderbolt$cachedSimulationAttempts, thunderbolt$fastFailures,
                result == null ? "null" : result.getClass().getSimpleName());
        thunderbolt$calculationStartedNanos = 0L;
    }

    @Inject(method = "runCraftAttempt", at = @At("HEAD"), cancellable = true, remap = false)
    private void ae2ltCore$fastAttempt(boolean simulate, long amount,
                                       CallbackInfoReturnable<CraftingPlan> cir) {
        thunderbolt$attempts++;
        if (!thunderbolt$isFastPlanningEnabled()) {
            return;
        }
        if (simulate
                && amount == requestedAmount
                && thunderbolt$cachedFullSimulationPlan != null) {
            thunderbolt$fastHandledAttempts++;
            thunderbolt$cachedSimulationAttempts++;
            this.simulate = true;
            cir.setReturnValue(thunderbolt$cachedFullSimulationPlan);
            thunderbolt$clearSimulationFallback();
            return;
        }
        var gridNode = simRequester.getGridNode();
        if (gridNode == null) {
            thunderbolt$fastFallbackAttempts++;
            return;
        }
        var craftingService = gridNode.getGrid().getCraftingService();

        FastPlanningWatchdog.start(
                "output=" + this.output + " requested=" + amount + " simulate=" + simulate + " engine=thunderbolt");
        try {
            var plannerSelection = PlannerDispatch.dispatch(new CraftingPlannerRequest(
                    craftingService,
                    simRequester,
                    new CraftingInventoryView() {
                        @Override
                        public long available(AEKey key) {
                            return Math.max(0L, networkInv.extract(key, Long.MAX_VALUE, Actionable.SIMULATE));
                        }

                        @Override
                        public List<AEKey> fuzzyCandidates(AEKey template) {
                            var candidates = new ArrayList<AEKey>();
                            for (var candidate : networkInv.findFuzzyTemplates(template)) {
                                candidates.add(candidate);
                            }
                            return List.copyOf(candidates);
                        }
                    },
                    getLevel(), output, amount, simulate));
            if (plannerSelection.handled()) {
                var plannerResult = plannerSelection.result();
                if (plannerResult.plan() != null
                        && !(plannerResult.plan() instanceof CraftingPlan)) {
                    throw new IllegalStateException("Planner " + plannerSelection.plannerId()
                            + " returned an ICraftingPlan that AE2 cannot use for runCraftAttempt: "
                            + plannerResult.plan().getClass().getName());
                }
                var plannerPlan = (CraftingPlan) plannerResult.plan();
                if (plannerResult.status() == CraftingPlannerStatus.EXACT_INFEASIBLE && !simulate) {
                    this.simulate = false;
                    if (plannerPlan != null && amount == requestedAmount) {
                        thunderbolt$cachedFullSimulationPlan = plannerPlan;
                    }
                    thunderbolt$fastHandledAttempts++;
                    cir.setReturnValue(null);
                    return;
                }
                if (plannerPlan != null) {
                    this.simulate = simulate;
                    thunderbolt$fastHandledAttempts++;
                    cir.setReturnValue(plannerPlan);
                    return;
                }
                ThunderboltCore.LOGGER.warn(
                        "[Thunderbolt Core] terminal planner {} returned {} without a usable plan; "
                                + "falling back to AE2",
                        plannerSelection.plannerId(), plannerResult.status());
                thunderbolt$fastFallbackAttempts++;
                return;
            }
            var attempt = FastCraftingPlanner.tryAttempt(
                    craftingService, networkInv, getLevel(), output, amount, simulate,
                    simRequester instanceof CraftingStockPolicy policy ? policy : null);
            if (attempt.handled()) {
                thunderbolt$fastHandledAttempts++;
                // Reproduce the side effect of the real method body we are skipping, so that
                // CraftingCalculation#isSimulation() reflects the attempt that produced this plan.
                this.simulate = simulate;
                if (!simulate
                        && amount == requestedAmount
                        && attempt.simulationFallback() != null) {
                    thunderbolt$cachedFullSimulationPlan = attempt.simulationFallback();
                    PlanningMetadataStore.record(
                            thunderbolt$cachedFullSimulationPlan, attempt.usedReusableStock());
                }
                if (attempt.plan() != null) {
                    PlanningMetadataStore.record(attempt.plan(), attempt.usedReusableStock());
                }
                cir.setReturnValue(attempt.plan());
            } else {
                thunderbolt$fastFallbackAttempts++;
            }
        } catch (Throwable t) {
            thunderbolt$fastFailures++;
            thunderbolt$fastFallbackAttempts++;
            // Never let the optimization break a craft: fall back to AE2. Log at WARN with full context
            // so an unexpected fast-path failure is easy to pinpoint instead of silently degrading.
            ThunderboltCore.LOGGER.warn(
                "[Thunderbolt Core] fast path threw, falling back to AE2: output={} amount={} simulate={}",
                output, amount, simulate, t);
        } finally {
            FastPlanningWatchdog.stop();
        }
    }

    @Unique
    private void thunderbolt$clearSimulationFallback() {
        thunderbolt$cachedFullSimulationPlan = null;
    }
}
