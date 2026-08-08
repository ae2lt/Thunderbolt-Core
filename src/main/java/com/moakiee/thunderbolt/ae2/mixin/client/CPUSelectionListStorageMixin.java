package com.moakiee.thunderbolt.ae2.mixin.client;

import appeng.client.gui.widgets.CPUSelectionList;
import appeng.menu.me.crafting.CraftingStatusMenu.CraftingCpuListEntry;
import com.moakiee.thunderbolt.ae2.client.InfiniteCpuStorageFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(
   value = {CPUSelectionList.class},
   remap = false
)
public abstract class CPUSelectionListStorageMixin {
   @Inject(
      method = {"formatStorage"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void thunderbolt$formatInfiniteStorage(CraftingCpuListEntry cpu, CallbackInfoReturnable<String> cir) {
      String formatted = InfiniteCpuStorageFormat.format(cpu.storage());
      if (formatted != null) {
         cir.setReturnValue(formatted);
      }
   }
}
