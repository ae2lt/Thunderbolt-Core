package com.moakiee.thunderbolt.core.crafting.algorithm.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

import com.moakiee.thunderbolt.ThunderboltCore;
import com.moakiee.thunderbolt.core.crafting.algorithm.ThunderboltMenus;

@EventBusSubscriber(
        modid = ThunderboltCore.MODID,
        value = Dist.CLIENT)
public final class ThunderboltClientEvents {
    private ThunderboltClientEvents() {
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(
                ThunderboltMenus.CRAFTING_ALGORITHM_PROVIDER.get(),
                CraftingAlgorithmProviderScreen::new);
    }
}
