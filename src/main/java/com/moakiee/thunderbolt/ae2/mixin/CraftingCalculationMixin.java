package com.moakiee.thunderbolt.ae2.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.NetworkCraftingSimulationState;

import com.moakiee.thunderbolt.ThunderboltCore;
import com.moakiee.thunderbolt.CoreConfig;
import com.moakiee.thunderbolt.ae2.crafting.FastCraftingControl;
import com.moakiee.thunderbolt.ae2.crafting.FastCraftingPlanner;
import com.moakiee.thunderbolt.ae2.crafting.FastPlanningWatchdog;

/**
 * Installs the linear-time autocrafting fast path inside AE2's per-amount attempt
 * ({@code CraftingCalculation#runCraftAttempt(boolean, long)}).
 *
 * <p>By hooking the per-amount attempt instead of {@code computePlan}, AE2 keeps driving its own
 * strategy and binary-search loop (no need to reimplement CRAFT_LESS); we only replace the expensive
 * tree simulation of each attempt. The planner is best-effort and never falls back to AE2's exhaustive
 * simulator (Policy A) — that quadratic/NBT-fuzzy path is exactly what hangs on heavy graphs.
 *
 * <p>Gating: {@link #ae2lt$fastPlanningEnabled} defaults to {@link CoreConfig#FAST_PATH_ENABLED} so the
 * lib accelerates every calculation when running standalone. A host mod (AE2 Lightning Tech) can call
 * {@link FastCraftingControl#ae2lt$setFastPlanningEnabled(boolean)} on a fresh calculation to restrict
 * the fast path to specific jobs (e.g. only when a TimeWheel CPU is active).
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
    abstract net.minecraft.world.level.Level getLevel();

    @Unique
    private boolean ae2lt$fastPlanningEnabled = CoreConfig.FAST_PATH_ENABLED;

    @Override
    public void ae2lt$setFastPlanningEnabled(boolean enabled) {
        this.ae2lt$fastPlanningEnabled = enabled;
    }

    @Override
    public boolean ae2lt$isFastPlanningEnabled() {
        return this.ae2lt$fastPlanningEnabled;
    }

    @Inject(method = "runCraftAttempt", at = @At("HEAD"), cancellable = true, remap = false)
    private void ae2ltCore$fastAttempt(boolean simulate, long amount,
                                       CallbackInfoReturnable<CraftingPlan> cir) {
        if (!this.ae2lt$fastPlanningEnabled) {
            return;
        }
        var gridNode = simRequester.getGridNode();
        if (gridNode == null) {
            return;
        }
        var craftingService = gridNode.getGrid().getCraftingService();

        FastPlanningWatchdog.start(
                "output=" + this.output + " requested=" + amount + " simulate=" + simulate + " engine=thunderbolt");
        try {
            var attempt = FastCraftingPlanner.tryAttempt(
                    craftingService, networkInv, getLevel(), output, amount, simulate,
                    simRequester instanceof com.moakiee.thunderbolt.ae2.crafting.ReservedStockCraftingRequester reserved
                            ? reserved : null);
            if (attempt.handled()) {
                // Reproduce the side effect of the real method body we are skipping, so that
                // CraftingCalculation#isSimulation() reflects the attempt that produced this plan.
                this.simulate = simulate;
                cir.setReturnValue(attempt.plan());
            }
        } catch (Throwable t) {
            // Never let the optimization break a craft: fall back to AE2. Log at WARN with full context
            // so an unexpected fast-path failure is easy to pinpoint instead of silently degrading.
            ThunderboltCore.LOGGER.warn(
                "[Thunderbolt Core] fast path threw, falling back to AE2: output={} amount={} simulate={}",
                output, amount, simulate, t);
        } finally {
            FastPlanningWatchdog.stop();
        }
    }
}
