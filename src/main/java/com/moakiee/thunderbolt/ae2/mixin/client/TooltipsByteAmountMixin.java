package com.moakiee.thunderbolt.ae2.mixin.client;

import appeng.core.localization.Tooltips;
import appeng.core.localization.Tooltips.Amount;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(
   value = {Tooltips.class},
   remap = false
)
public final class TooltipsByteAmountMixin {
   private TooltipsByteAmountMixin() {
   }

   @Inject(
      method = {"getByteAmount"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private static void thunderbolt$getInfiniteByteAmount(long amount, CallbackInfoReturnable<Amount> cir) {
      if (amount == Long.MAX_VALUE) {
         cir.setReturnValue(new Amount("∞", ""));
      }
   }
}
