package com.moakiee.thunderbolt.mixin.ae2.crafting;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import appeng.me.service.CraftingService;
import appeng.me.service.helpers.NetworkCraftingProviders;

@Mixin(value = CraftingService.class, remap = false)
public interface CraftingServiceAccessor {
    @Accessor("craftingProviders")
    NetworkCraftingProviders thunderbolt$getCraftingProviders();
}
