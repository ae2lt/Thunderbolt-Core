package com.moakiee.thunderbolt.ae2.mixin;

import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.api.crafting.IPatternDetails;
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
// Forge 1.20.1 EAEP 1.5.5 adds the synthetic methods from a priority-500 mixin and its
// AdvancedAE callback from priority 450. Lower-priority mixins are applied later, so 400
// ensures both targets exist before these injections are resolved. The binary-shape test
// locks those upstream contracts to the exact 1.20.1 artifact.
@Mixin(value = PatternProviderLogic.class, priority = 400, remap = false)
public abstract class ExtendedAePlusVirtualCompletionSuppressionMixin {
    /**
     * EAEP's vanilla-AE2 callback invokes this private helper directly, and the helper reads its
     * backing field instead of the public bridge. Cancel it while the time-wheel owns the push.
     */
    @Dynamic("Added to PatternProviderLogic by ExtendedAE Plus")
    @Inject(
            method = "eap$compatTryVirtualCompletion",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private void thunderbolt$suppressNestedVanillaCpuScan(
            IPatternDetails pattern, CallbackInfo ci) {
        if (ExtendedAePlusVirtualCraftingContext.isTimeWheelProviderPushActive()) {
            ci.cancel();
        }
    }

    /**
     * EAEP's AdvancedAE callback asks this public bridge for the card state before scanning CPUs.
     * Hiding it only for the synchronous time-wheel push makes that callback return early; the
     * time-wheel reads the real state after the provider call has returned.
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
