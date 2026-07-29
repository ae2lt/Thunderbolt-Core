package com.moakiee.thunderbolt.ae2.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.helpers.patternprovider.PatternProviderLogic;
import com.moakiee.thunderbolt.ae2.timewheel.ExtendedAePlusVirtualCraftingContext;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents ExtendedAE Plus' global CPU scan while a time-wheel CPU owns the provider push.
 *
 * <p>The time-wheel CPU completes its own job after its dispatch bookkeeping is committed. Without
 * this guard, ExtendedAE Plus can complete an unrelated vanilla or AdvancedAE CPU using the same
 * pattern before the provider call returns.
 */
@Mixin(value = PatternProviderLogic.class, priority = 800, remap = false)
public abstract class ExtendedAePlusVirtualCompletionSuppressionMixin {
    @Dynamic("Added to PatternProviderLogic by ExtendedAE Plus")
    @Inject(
            method = "eap$compatTryVirtualCompletion",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private void thunderbolt$suppressVanillaCpuScan(
            IPatternDetails patternDetails,
            CallbackInfo ci) {
        if (ExtendedAePlusVirtualCraftingContext.isTimeWheelProviderPushActive()) {
            ci.cancel();
        }
    }

    /**
     * The AdvancedAE compatibility callback asks the public bridge for the card state before it
     * scans CPUs. Hiding the state only for the synchronous time-wheel push makes that callback
     * return early; the time-wheel reads the real state after the provider call has returned.
     */
    @Dynamic("Added to PatternProviderLogic by ExtendedAE Plus")
    @Inject(
            method = "eap$compatIsVirtualCraftingEnabled",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private void thunderbolt$hideVirtualCardFromNestedCpuScans(
            CallbackInfoReturnable<Boolean> cir) {
        if (ExtendedAePlusVirtualCraftingContext.isTimeWheelProviderPushActive()) {
            cir.setReturnValue(false);
        }
    }
}
