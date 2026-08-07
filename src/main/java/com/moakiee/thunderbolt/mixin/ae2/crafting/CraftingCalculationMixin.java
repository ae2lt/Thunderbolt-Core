package com.moakiee.thunderbolt.mixin.ae2.crafting;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.NetworkCraftingSimulationState;

import com.moakiee.thunderbolt.ThunderboltCore;
import com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngines;
import com.moakiee.thunderbolt.api.crafting.PlanningAttempt;
import com.moakiee.thunderbolt.api.crafting.PlanningChoice;
import com.moakiee.thunderbolt.api.crafting.PlanningEngineSession;
import com.moakiee.thunderbolt.api.crafting.PlanningRequest;
import com.moakiee.thunderbolt.core.crafting.algorithm.CraftingAlgorithmCalculationStatus;
import com.moakiee.thunderbolt.core.crafting.algorithm.CraftingPlanningControl;
import com.moakiee.thunderbolt.core.crafting.algorithm.ThunderboltV2PlanningEngine;
import com.moakiee.thunderbolt.core.crafting.plan.LoopCraftingPlan;
import com.moakiee.thunderbolt.core.crafting.planner.PlanningMetadataStore;
import com.moakiee.thunderbolt.core.crafting.support.FastCraftingControl;
import com.moakiee.thunderbolt.core.crafting.support.FastPlanningWatchdog;

/** Selects and locks one planning engine for the complete AE2 CraftingCalculation. */
@Mixin(value = CraftingCalculation.class, remap = false)
public abstract class CraftingCalculationMixin implements CraftingPlanningControl, FastCraftingControl {
    @Shadow
    @Final
    private NetworkCraftingSimulationState networkInv;

    @Shadow
    @Final
    private AEKey output;

    @Shadow
    @Final
    private long requestedAmount;

    @Shadow
    @Final
    private CalculationStrategy strategy;

    @Shadow
    @Final
    ICraftingSimulationRequester simRequester;

    @Shadow
    private boolean simulate;

    @Shadow
    abstract net.minecraft.world.level.Level getLevel();

    @Unique
    private List<PlanningChoice> thunderbolt$candidates = List.of(PlanningChoice.VANILLA);

    @Unique
    @Nullable
    private IGrid thunderbolt$grid;

    @Unique
    @Nullable
    private PlanningRequest thunderbolt$request;

    @Unique
    @Nullable
    private PlanningEngineSession thunderbolt$selectedSession;

    @Unique
    @Nullable
    private net.minecraft.resources.ResourceLocation thunderbolt$selectedEngine;

    @Unique
    private boolean thunderbolt$selectedVanilla;

    @Unique
    @Nullable
    private CraftingPlan thunderbolt$cachedFullSimulationPlan;

    @Unique
    private long thunderbolt$calculationStartedNanos;

    @Unique
    private int thunderbolt$attempts;

    @Unique
    private int thunderbolt$handledAttempts;

    @Unique
    private int thunderbolt$declinedEngines;

    @Override
    public void thunderbolt$configurePlanning(
            List<PlanningChoice> candidates, CalculationStrategy ignoredStrategy) {
        if (candidates == null || candidates.isEmpty()
                || candidates.getLast().kind() != PlanningChoice.Kind.VANILLA) {
            throw new IllegalArgumentException("Resolved planning candidates must end in VANILLA");
        }
        this.thunderbolt$candidates = List.copyOf(candidates);
    }

    /** Compatibility bridge for integrations compiled against the boolean fast-planner hook. */
    @Override
    public void thunderbolt$setFastPlanningEnabled(boolean enabled) {
        thunderbolt$candidates = enabled
                ? List.of(PlanningChoice.engine(ThunderboltV2PlanningEngine.ID), PlanningChoice.VANILLA)
                : List.of(PlanningChoice.VANILLA);
    }

    @Override
    public boolean thunderbolt$isFastPlanningEnabled() {
        return thunderbolt$candidates.stream().anyMatch(choice ->
                choice.kind() == PlanningChoice.Kind.ENGINE
                        && ThunderboltV2PlanningEngine.ID.equals(choice.engineId()));
    }

    @Inject(method = "run", at = @At("HEAD"), remap = false)
    private void thunderbolt$startCalculation(CallbackInfoReturnable<ICraftingPlan> cir) {
        thunderbolt$calculationStartedNanos = System.nanoTime();
        thunderbolt$attempts = 0;
        thunderbolt$handledAttempts = 0;
        thunderbolt$declinedEngines = 0;
        thunderbolt$selectedSession = null;
        thunderbolt$selectedEngine = null;
        thunderbolt$selectedVanilla = false;
        thunderbolt$cachedFullSimulationPlan = null;

        var node = simRequester.getGridNode();
        if (node != null) {
            thunderbolt$grid = node.getGrid();
            thunderbolt$request = new PlanningRequest(
                    getLevel(), thunderbolt$grid.getCraftingService(), networkInv, output,
                    requestedAmount, strategy, simRequester, null);
        } else {
            thunderbolt$grid = null;
            thunderbolt$request = null;
            thunderbolt$candidates = List.of(PlanningChoice.VANILLA);
        }
        ThunderboltCore.LOGGER.info(
                "[Thunderbolt Core][crafting-planner] started: output={} requested={} candidates={}",
                output, requestedAmount, thunderbolt$candidates);
    }

    @Inject(method = "run", at = @At("RETURN"), cancellable = true, remap = false)
    private void thunderbolt$finishCalculation(CallbackInfoReturnable<ICraftingPlan> cir) {
        var result = cir.getReturnValue();
        if (thunderbolt$selectedSession != null) {
            result = thunderbolt$selectedSession.finish(result);
        }
        if (result instanceof CraftingPlan craftingPlan) {
            result = LoopCraftingPlan.wrapIfNeeded(
                    craftingPlan, PlanningMetadataStore.take(craftingPlan));
        }
        cir.setReturnValue(result);
        double wallMs = TimeUnit.NANOSECONDS.toMicros(Math.max(
                0L, System.nanoTime() - thunderbolt$calculationStartedNanos)) / 1_000.0D;
        ThunderboltCore.LOGGER.info(
                "[Thunderbolt Core][crafting-planner] finished: output={} requested={} wallMs={} "
                        + "selected={} attempts={} handled={} declinedEngines={} result={}",
                output, requestedAmount, wallMs,
                thunderbolt$selectedVanilla ? "vanilla" : thunderbolt$selectedEngine,
                thunderbolt$attempts, thunderbolt$handledAttempts, thunderbolt$declinedEngines,
                result == null ? "null" : result.getClass().getSimpleName());
        thunderbolt$selectedSession = null;
        thunderbolt$request = null;
        thunderbolt$grid = null;
        thunderbolt$cachedFullSimulationPlan = null;
    }

    @Inject(method = "runCraftAttempt", at = @At("HEAD"), cancellable = true, remap = false)
    private void thunderbolt$planAttempt(
            boolean simulate, long amount, CallbackInfoReturnable<CraftingPlan> cir) {
        thunderbolt$attempts++;
        if (simulate && amount == requestedAmount && thunderbolt$cachedFullSimulationPlan != null) {
            this.simulate = true;
            thunderbolt$handledAttempts++;
            var cached = thunderbolt$cachedFullSimulationPlan;
            thunderbolt$cachedFullSimulationPlan = null;
            cir.setReturnValue(cached);
            return;
        }
        if (thunderbolt$selectedVanilla) {
            return;
        }
        if (thunderbolt$selectedSession != null) {
            PlanningAttempt attempt = thunderbolt$invokeSelected(amount, simulate);
            if (attempt.status() == PlanningAttempt.Status.DECLINE) {
                throw new IllegalStateException("Selected planning engine " + thunderbolt$selectedEngine
                        + " declined a later probe in the same calculation");
            }
            thunderbolt$handle(attempt, simulate, amount, cir);
            return;
        }
        if (thunderbolt$grid == null || thunderbolt$request == null) {
            thunderbolt$selectVanilla();
            return;
        }

        for (var choice : thunderbolt$candidates) {
            if (choice.kind() == PlanningChoice.Kind.VANILLA) {
                thunderbolt$selectVanilla();
                return;
            }
            var engine = CraftingPlanningEngines.get(choice.engineId());
            if (engine == null) {
                thunderbolt$declinedEngines++;
                continue;
            }
            try {
                if (!engine.check(thunderbolt$grid, thunderbolt$request)) {
                    thunderbolt$declinedEngines++;
                    continue;
                }
                var candidateSession = engine.createSession(thunderbolt$grid, thunderbolt$request);
                PlanningAttempt attempt = thunderbolt$invoke(
                        choice.engineId(), candidateSession, amount, simulate);
                if (attempt.status() == PlanningAttempt.Status.DECLINE) {
                    thunderbolt$declinedEngines++;
                    continue;
                }
                thunderbolt$selectedEngine = choice.engineId();
                thunderbolt$selectedSession = candidateSession;
                CraftingAlgorithmCalculationStatus.select(
                        simRequester, thunderbolt$selectedEngine);
                thunderbolt$handle(attempt, simulate, amount, cir);
                return;
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (RuntimeException failure) {
                thunderbolt$declinedEngines++;
                ThunderboltCore.LOGGER.warn(
                        "[Thunderbolt Core] planning engine failed before selection; trying next: "
                                + "engine={} output={} amount={} simulate={}",
                        choice.engineId(), output, amount, simulate, failure);
            }
        }
        thunderbolt$selectVanilla();
    }

    @Unique
    private void thunderbolt$selectVanilla() {
        thunderbolt$selectedVanilla = true;
        CraftingAlgorithmCalculationStatus.select(
                simRequester, CraftingPlanningEngines.VANILLA_ID);
    }

    @Unique
    private PlanningAttempt thunderbolt$invokeSelected(long amount, boolean simulate) {
        return thunderbolt$invoke(
                thunderbolt$selectedEngine, thunderbolt$selectedSession, amount, simulate);
    }

    @Unique
    private PlanningAttempt thunderbolt$invoke(
            net.minecraft.resources.ResourceLocation engineId,
            PlanningEngineSession session,
            long amount,
            boolean simulate) {
        FastPlanningWatchdog.start(
                "output=" + output + " requested=" + amount + " simulate=" + simulate
                        + " engine=" + engineId);
        try {
            return session.attempt(amount, simulate);
        } finally {
            FastPlanningWatchdog.stop();
        }
    }

    @Unique
    private void thunderbolt$handle(
            PlanningAttempt attempt,
            boolean simulate,
            long amount,
            CallbackInfoReturnable<CraftingPlan> cir) {
        thunderbolt$handledAttempts++;
        this.simulate = simulate;
        if (!simulate && amount == requestedAmount && attempt.simulationFallback() != null) {
            thunderbolt$cachedFullSimulationPlan = attempt.simulationFallback();
        }
        cir.setReturnValue(attempt.plan());
    }
}
