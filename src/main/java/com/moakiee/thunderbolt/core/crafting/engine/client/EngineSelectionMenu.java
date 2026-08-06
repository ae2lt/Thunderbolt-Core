package com.moakiee.thunderbolt.core.crafting.engine.client;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;

import appeng.menu.AEBaseMenu;

public class EngineSelectionMenu extends AEBaseMenu {

    public static final MenuType<EngineSelectionMenu> TYPE = new MenuType<>(
            EngineSelectionMenu::new, FeatureFlags.DEFAULT_FLAGS);

    public EngineSelectionMenu(int id, Inventory playerInventory) {
        super(TYPE, id, playerInventory, null);
    }
}
