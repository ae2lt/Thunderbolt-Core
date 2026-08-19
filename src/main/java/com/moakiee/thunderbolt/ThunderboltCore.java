package com.moakiee.thunderbolt;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import appeng.api.networking.GridServices;
import appeng.api.storage.StorageCells;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.spongepowered.asm.mixin.MixinEnvironment;

import com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngines;
import com.moakiee.thunderbolt.api.crafting.ICraftingPlanningService;
import com.moakiee.thunderbolt.api.eject.EjectCapabilityRegistry;
import com.moakiee.thunderbolt.core.crafting.algorithm.CraftingPlanningService;
import com.moakiee.thunderbolt.core.crafting.algorithm.ThunderboltMenus;
import com.moakiee.thunderbolt.core.crafting.planner.ThunderboltV2PlanningEngine;
import com.moakiee.thunderbolt.core.eject.EjectEndpointIndex;
import com.moakiee.thunderbolt.core.eject.ThunderboltBlockEntities;
import com.moakiee.thunderbolt.core.storage.cell.IndexedCellStorageRegistry;
import com.moakiee.thunderbolt.core.storage.cell.IndexedStorageCellHandler;

/** Entry point for Thunderbolt Core's shared AE2 optimization and extension layer. */
@Mod(ThunderboltCore.MODID)
public final class ThunderboltCore {
    public static final String MODID = "thunderbolt";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ThunderboltCore() {
        // No-arg @Mod constructor: the only style accepted by every 1.20.1 loader
        // (Forge 47.2.x requires no-arg; NeoForge 47.1.x tries no-arg first;
        // Forge 47.4.x falls back to it). Fetch the bus via the static context.
        var modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        EjectCapabilityRegistry.installRuntime(EjectEndpointIndex.INSTANCE);
        ThunderboltBlockEntities.TYPES.register(modEventBus);
        ThunderboltMenus.TYPES.register(modEventBus);
        modEventBus.addListener(this::onCommonSetup);
        modEventBus.addListener(this::onLoadComplete);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarting);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopped);
        LOGGER.info("[Thunderbolt Core] initialized");
    }

    private void onServerStarting(ServerStartingEvent event) {
        EjectEndpointIndex.INSTANCE.onServerStart(event.getServer());
        IndexedCellStorageRegistry.get(event.getServer());
    }

    private void onServerStopped(ServerStoppedEvent event) {
        EjectEndpointIndex.INSTANCE.onServerStop();
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            StorageCells.addCellHandler(IndexedStorageCellHandler.INSTANCE);
            GridServices.register(ICraftingPlanningService.class, CraftingPlanningService.class);
            CraftingPlanningEngines.register(
                    ThunderboltV2PlanningEngine.INSTANCE, 1_000, false);
        });
    }

    private void onLoadComplete(FMLLoadCompleteEvent event) {
        if (Boolean.getBoolean("thunderbolt.mixinAudit")) {
            event.enqueueWork(() -> {
                LOGGER.info("Auditing every selected Thunderbolt Mixin target");
                MixinEnvironment.getCurrentEnvironment().audit();
            });
        }
    }
}
