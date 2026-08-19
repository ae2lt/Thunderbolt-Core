package com.moakiee.thunderbolt.mixin.ae2.crafting;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.crafting.CraftingLinkNexus;

// CraftingLinkNexus is an AE2 class with no obfuscation mappings in the Forge dev environment — remap must be off.
@Mixin(value = CraftingLinkNexus.class, remap = false)
public abstract class CraftingLinkNexusMixin {

    @WrapOperation(
            method = "isDead",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/api/networking/IGridNode;getGrid()Lappeng/api/networking/IGrid;"))
    private IGrid thunderbolt$allowTemporarilyMissingRequesterNode(
            @Nullable IGridNode node, Operation<IGrid> original) {
        // Requesters can temporarily lose their node while chunks unload or reconnect.
        return node != null ? original.call(node) : null;
    }
}
