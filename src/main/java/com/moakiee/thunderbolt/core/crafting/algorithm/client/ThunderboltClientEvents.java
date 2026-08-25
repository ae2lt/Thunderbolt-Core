package com.moakiee.thunderbolt.core.crafting.algorithm.client;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import appeng.client.gui.style.StyleManager;

import com.moakiee.thunderbolt.ThunderboltCore;
import com.moakiee.thunderbolt.core.crafting.algorithm.ThunderboltMenus;
import com.moakiee.thunderbolt.core.crafting.algorithm.menu.CraftingAlgorithmProviderMenu;

@Mod.EventBusSubscriber(
        modid = ThunderboltCore.MODID,
        bus = Mod.EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT)
public final class ThunderboltClientEvents {
    private ThunderboltClientEvents() {
    }

    @SubscribeEvent
    public static void registerScreens(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(
                ThunderboltMenus.CRAFTING_ALGORITHM_PROVIDER.get(),
                ThunderboltClientEvents::createCraftingAlgorithmProviderScreen));
    }

    private static CraftingAlgorithmProviderScreen createCraftingAlgorithmProviderScreen(
            CraftingAlgorithmProviderMenu menu, Inventory inventory, Component title) {
        var style = StyleManager.loadStyleDoc(
                "/screens/thunderbolt/crafting_algorithm_provider.json");
        return new CraftingAlgorithmProviderScreen(menu, inventory, title, style);
    }
}
