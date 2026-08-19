package com.moakiee.thunderbolt.core.crafting.algorithm;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;

import appeng.menu.AEBaseMenu;
import appeng.menu.implementations.MenuTypeBuilder;

/** Forge 1.20.1 backport of AE2's later unregistered menu-builder endpoint. */
public interface ForgeMenuTypeBuilderExtension<M extends AEBaseMenu> {
    MenuType<M> thunderbolt$buildUnregistered(ResourceLocation id);

    @SuppressWarnings("unchecked")
    static <M extends AEBaseMenu> MenuType<M> buildUnregistered(
            MenuTypeBuilder<M, ?> builder, ResourceLocation id) {
        return ((ForgeMenuTypeBuilderExtension<M>) (Object) builder)
                .thunderbolt$buildUnregistered(id);
    }
}
