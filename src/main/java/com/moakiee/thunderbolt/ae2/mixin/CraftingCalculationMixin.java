package com.moakiee.thunderbolt.ae2.mixin;

import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.NetworkCraftingSimulationState;
import com.moakiee.thunderbolt.ThunderboltCore;
import com.moakiee.thunderbolt.ae2.crafting.FastCraftingControl;
import com.moakiee.thunderbolt.ae2.crafting.FastCraftingPlanner;
import com.moakiee.thunderbolt.ae2.crafting.FastPlanningWatchdog;
import com.moakiee.thunderbolt.ae2.crafting.LoopCraftingPlan;
import com.moakiee.thunderbolt.ae2.crafting.ReservedStockCraftingRequester;
import com.moakiee.thunderbolt.core.planner.ReusableStockUsageKey;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(
   value = {CraftingCalculation.class},
   remap = false
)
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
   @Unique
   private boolean ae2lt$fastPlanningInitialized;
   @Unique
   private boolean ae2lt$fastPlanningEnabled;
   @Unique
   @Nullable
   private Map<CraftingPlan, Map<ReusableStockUsageKey<AEKey>, Long>> thunderbolt$reusableStockByAttempt;
   @Unique
   @Nullable
   private CraftingPlan thunderbolt$cachedFullSimulationPlan;
   @Unique
   private Map<ReusableStockUsageKey<AEKey>, Long> thunderbolt$cachedFullSimulationReusableStock = Map.of();
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

   @Shadow
   abstract Level getLevel();

   @Override
   public void ae2lt$setFastPlanningEnabled(boolean enabled) {
      this.ae2lt$fastPlanningInitialized = true;
      this.ae2lt$fastPlanningEnabled = enabled;
   }

   @Override
   public boolean ae2lt$isFastPlanningEnabled() {
      return this.ae2lt$fastPlanningInitialized && this.ae2lt$fastPlanningEnabled;
   }

   @Inject(
      method = {"run"},
      at = {@At("HEAD")},
      remap = false
   )
   private void thunderbolt$startCalculationTiming(CallbackInfoReturnable<ICraftingPlan> cir) {
      this.thunderbolt$calculationStartedNanos = System.nanoTime();
      this.thunderbolt$attempts = 0;
      this.thunderbolt$fastHandledAttempts = 0;
      this.thunderbolt$fastFallbackAttempts = 0;
      this.thunderbolt$cachedSimulationAttempts = 0;
      this.thunderbolt$fastFailures = 0;
      if (this.ae2lt$isFastPlanningEnabled()) {
         ThunderboltCore.LOGGER.debug("[Thunderbolt Core][crafting-timing] started: output={} requested={}", this.output, this.requestedAmount);
      }
   }

   @Inject(
      method = {"run"},
      at = {@At("RETURN")},
      cancellable = true,
      remap = false
   )
   private void thunderbolt$wrapLoopPlan(CallbackInfoReturnable<ICraftingPlan> cir) {
      ICraftingPlan result = (ICraftingPlan)cir.getReturnValue();
      Map<CraftingPlan, Map<ReusableStockUsageKey<AEKey>, Long>> reusableStockByAttempt = this.thunderbolt$getReusableStockByAttempt();
      Map<ReusableStockUsageKey<AEKey>, Long> usedReusableStock = null;
      if (result instanceof CraftingPlan craftingPlan) {
         usedReusableStock = reusableStockByAttempt.get(craftingPlan);
      }

      cir.setReturnValue(LoopCraftingPlan.wrapIfNeeded(result, usedReusableStock));
      reusableStockByAttempt.clear();
      this.thunderbolt$clearSimulationFallback();
      long elapsedNanos = this.thunderbolt$calculationStartedNanos == 0L ? 0L : Math.max(0L, System.nanoTime() - this.thunderbolt$calculationStartedNanos);
      double wallMs = (double)TimeUnit.NANOSECONDS.toMicros(elapsedNanos) / 1000.0;
      if (this.ae2lt$isFastPlanningEnabled()) {
         ThunderboltCore.LOGGER
            .info(
               "[Thunderbolt Core][crafting-timing] finished: output={} requested={} wallMs={} attempts={} fastHandled={} fastFallback={} cachedSimulation={} fastFailures={} result={}",
               new Object[]{
                  this.output,
                  this.requestedAmount,
                  wallMs,
                  this.thunderbolt$attempts,
                  this.thunderbolt$fastHandledAttempts,
                  this.thunderbolt$fastFallbackAttempts,
                  this.thunderbolt$cachedSimulationAttempts,
                  this.thunderbolt$fastFailures,
                  result == null ? "null" : result.getClass().getSimpleName()
               }
            );
      }

      this.thunderbolt$calculationStartedNanos = 0L;
   }

   @Inject(
      method = {"runCraftAttempt"},
      at = {@At("HEAD")},
      cancellable = true,
      remap = false
   )
   private void ae2ltCore$fastAttempt(boolean simulate, long amount, CallbackInfoReturnable<CraftingPlan> cir) {
      this.thunderbolt$attempts++;
      if (this.ae2lt$isFastPlanningEnabled()) {
         if (simulate && amount == this.requestedAmount && this.thunderbolt$cachedFullSimulationPlan != null) {
            this.thunderbolt$fastHandledAttempts++;
            this.thunderbolt$cachedSimulationAttempts++;
            this.simulate = true;
            this.thunderbolt$getReusableStockByAttempt().put(this.thunderbolt$cachedFullSimulationPlan, this.thunderbolt$cachedFullSimulationReusableStock);
            cir.setReturnValue(this.thunderbolt$cachedFullSimulationPlan);
            this.thunderbolt$clearSimulationFallback();
         } else {
            IGridNode gridNode = this.simRequester.getGridNode();
            if (gridNode == null) {
               this.thunderbolt$fastFallbackAttempts++;
            } else {
               ICraftingService craftingService = gridNode.getGrid().getCraftingService();
               FastPlanningWatchdog.start("output=" + this.output + " requested=" + amount + " simulate=" + simulate + " engine=thunderbolt");

               try {
                  FastCraftingPlanner.FastAttempt attempt = FastCraftingPlanner.tryAttempt(
                     craftingService,
                     this.networkInv,
                     this.getLevel(),
                     this.output,
                     amount,
                     simulate,
                     this.simRequester instanceof ReservedStockCraftingRequester reserved ? reserved : null
                  );
                  if (attempt.handled()) {
                     this.thunderbolt$fastHandledAttempts++;
                     this.simulate = simulate;
                     if (!simulate && amount == this.requestedAmount && attempt.simulationFallback() != null) {
                        this.thunderbolt$cachedFullSimulationPlan = attempt.simulationFallback();
                        this.thunderbolt$cachedFullSimulationReusableStock = attempt.usedReusableStock();
                     }

                     if (attempt.plan() != null) {
                        this.thunderbolt$getReusableStockByAttempt().put(attempt.plan(), attempt.usedReusableStock());
                     }

                     cir.setReturnValue(attempt.plan());
                  } else {
                     this.thunderbolt$fastFallbackAttempts++;
                  }
               } catch (Throwable var13) {
                  this.thunderbolt$fastFailures++;
                  this.thunderbolt$fastFallbackAttempts++;
                  ThunderboltCore.LOGGER
                     .warn(
                        "[Thunderbolt Core] fast path threw, falling back to AE2: output={} amount={} simulate={}",
                        new Object[]{this.output, amount, simulate, var13}
                     );
               } finally {
                  FastPlanningWatchdog.stop();
               }
            }
         }
      }
   }

   @Unique
   private Map<CraftingPlan, Map<ReusableStockUsageKey<AEKey>, Long>> thunderbolt$getReusableStockByAttempt() {
      if (this.thunderbolt$reusableStockByAttempt == null) {
         this.thunderbolt$reusableStockByAttempt = new IdentityHashMap<>();
      }

      return this.thunderbolt$reusableStockByAttempt;
   }

   @Unique
   private void thunderbolt$clearSimulationFallback() {
      this.thunderbolt$cachedFullSimulationPlan = null;
      this.thunderbolt$cachedFullSimulationReusableStock = Map.of();
   }
}
