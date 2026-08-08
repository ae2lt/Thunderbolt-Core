package com.moakiee.thunderbolt.ae2.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.crafting.CraftingLinkNexus;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = CraftingLinkNexus.class, remap = false)
public abstract class CraftingLinkNexusMixin {
   @WrapOperation(
      method = {"isDead"},
      at = {@At(
         value = "INVOKE",
         target = "Lappeng/api/networking/IGridNode;getGrid()Lappeng/api/networking/IGrid;",
         remap = false
      )},
      remap = false
   )
   private IGrid thunderbolt$allowTemporarilyMissingRequesterNode(@Nullable IGridNode node, Operation<IGrid> original) {
      return node != null ? (IGrid)original.call(new Object[]{node}) : null;
   }
}
