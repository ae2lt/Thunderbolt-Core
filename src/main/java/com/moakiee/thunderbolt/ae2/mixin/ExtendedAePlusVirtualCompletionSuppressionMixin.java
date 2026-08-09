package com.moakiee.thunderbolt.ae2.mixin;

import appeng.helpers.patternprovider.PatternProviderLogic;
import com.moakiee.thunderbolt.ae2.timewheel.ExtendedAePlusVirtualCraftingContext;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents ExtendedAE Plus' global CPU scan while a time-wheel CPU owns the provider push.
 *
 * <p>The time-wheel CPU completes its own job after its dispatch bookkeeping is committed. Without
 * this guard, ExtendedAE Plus can complete an unrelated vanilla or AdvancedAE CPU using the same
 * pattern before the provider call returns.
 */
// EAEP 1.5.5 adds eap$compatIsVirtualCraftingEnabled from a priority-900 mixin. Lower-priority
// mixins are applied later, so 800 ensures the synthetic bridge method exists before this
// injection is resolved. OptionalIntegrationBinaryShapeTest locks that upstream contract.
@Mixin(value = PatternProviderLogic.class, priority = 800, remap = false)
public abstract class ExtendedAePlusVirtualCompletionSuppressionMixin {
    /**
     * All three EAEP 1.5.5 virtual-completion callbacks (AE2, AdvancedAE, and NeoECO) ask this
     * public bridge for the card state before scanning CPUs. Hiding the state only for the
     * synchronous time-wheel push makes those callbacks return early; the time-wheel reads the
     * real state after the provider call has returned.
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
