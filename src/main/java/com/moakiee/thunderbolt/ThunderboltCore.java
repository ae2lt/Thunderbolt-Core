package com.moakiee.thunderbolt;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import appeng.api.networking.GridServices;
import appeng.api.storage.StorageCells;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngines;
import com.moakiee.thunderbolt.api.crafting.ICraftingPlanningService;
import com.moakiee.thunderbolt.api.eject.EjectCapabilityRegistry;
import com.moakiee.thunderbolt.core.crafting.algorithm.CraftingPlanningService;
import com.moakiee.thunderbolt.core.crafting.algorithm.ThunderboltMenus;
import com.moakiee.thunderbolt.core.crafting.algorithm.ThunderboltV2PlanningEngine;
import com.moakiee.thunderbolt.core.eject.EjectEndpointIndex;
import com.moakiee.thunderbolt.core.eject.ThunderboltBlockEntities;
import com.moakiee.thunderbolt.core.storage.cell.IndexedCellStorageRegistry;
import com.moakiee.thunderbolt.core.storage.cell.IndexedStorageCellHandler;

/** Entry point for Thunderbolt Core's shared AE2 optimization and extension layer. */
@Mod(ThunderboltCore.MODID)
public final class ThunderboltCore {
    public static final String MODID = "thunderbolt";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ThunderboltCore(IEventBus modEventBus) {
        EjectCapabilityRegistry.installRuntime(EjectEndpointIndex.INSTANCE);
        ThunderboltBlockEntities.TYPES.register(modEventBus);
        ThunderboltMenus.TYPES.register(modEventBus);
        modEventBus.addListener(this::onCommonSetup);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
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
                    ThunderboltV2PlanningEngine.INSTANCE, 1_000, true);
        });
    }
}
