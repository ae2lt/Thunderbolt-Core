package com.moakiee.thunderbolt.core.crafting.algorithm.client;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import com.moakiee.thunderbolt.ThunderboltCore;
import com.moakiee.thunderbolt.core.crafting.algorithm.ThunderboltMenus;

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
                CraftingAlgorithmProviderScreen::new));
    }
}
