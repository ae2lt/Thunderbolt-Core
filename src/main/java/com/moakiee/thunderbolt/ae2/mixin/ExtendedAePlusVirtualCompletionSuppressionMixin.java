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

@Mixin(
   value = {PatternProviderLogic.class},
   priority = 400,
   remap = false
)
public abstract class ExtendedAePlusVirtualCompletionSuppressionMixin {
   @Inject(
      method = {"eap$compatTryVirtualCompletion"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   @Dynamic("Added to PatternProviderLogic by ExtendedAE Plus")
   private void thunderbolt$suppressVanillaCpuScan(IPatternDetails patternDetails, CallbackInfo ci) {
      if (ExtendedAePlusVirtualCraftingContext.isTimeWheelProviderPushActive()) {
         ci.cancel();
      }
   }

   @Inject(
      method = {"eap$compatIsVirtualCraftingEnabled"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   @Dynamic("Added to PatternProviderLogic by ExtendedAE Plus")
   private void thunderbolt$hideVirtualCardFromNestedCpuScans(CallbackInfoReturnable<Boolean> cir) {
      if (ExtendedAePlusVirtualCraftingContext.isTimeWheelProviderPushActive()) {
         cir.setReturnValue(false);
      }
   }
}
