package com.moakiee.thunderbolt.mixin.ae2.crafting;

import com.google.common.base.Preconditions;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;

import appeng.menu.AEBaseMenu;
import appeng.menu.MenuOpener;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.locator.MenuLocator;

import com.moakiee.thunderbolt.core.crafting.algorithm.ForgeMenuTypeBuilderExtension;

/** Lets Thunderbolt register an AE2-hosted menu under its own namespace on Forge 1.20.1. */
@Mixin(value = MenuTypeBuilder.class, remap = false)
public abstract class MenuTypeBuilderMixin<M extends AEBaseMenu, I>
        implements ForgeMenuTypeBuilderExtension<M> {
    @Shadow
    @Nullable
    private ResourceLocation id;

    @Shadow
    private MenuType<M> menuType;

    @Invoker("fromNetwork")
    protected abstract M thunderbolt$fromNetwork(
            int containerId, Inventory inventory, FriendlyByteBuf buffer);

    @Invoker("open")
    protected abstract boolean thunderbolt$open(
            Player player, MenuLocator locator, boolean fromSubMenu);

    @Override
    public MenuType<M> thunderbolt$buildUnregistered(ResourceLocation id) {
        Preconditions.checkState(menuType == null, "build was already called");
        Preconditions.checkState(this.id == null, "id should not be set");

        this.id = id;
        this.menuType = IForgeMenuType.create(this::thunderbolt$fromNetwork);
        MenuOpener.addOpener(this.menuType, this::thunderbolt$open);
        return this.menuType;
    }
}
