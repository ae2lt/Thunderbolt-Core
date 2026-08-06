package com.moakiee.thunderbolt;

import com.mojang.logging.LogUtils;
import com.moakiee.thunderbolt.api.crafting.engine.CraftingEngineRegistry;
import com.moakiee.thunderbolt.api.eject.EjectCapabilityRegistry;
import com.moakiee.thunderbolt.core.crafting.engine.CraftingEngineCommand;
import com.moakiee.thunderbolt.core.crafting.engine.CraftingEngineConfig;
import com.moakiee.thunderbolt.core.crafting.engine.ThunderboltEngine;
import com.moakiee.thunderbolt.core.crafting.engine.net.CraftingEngineNetwork;
import com.moakiee.thunderbolt.core.storage.cell.IndexedCellStorageRegistry;
import com.moakiee.thunderbolt.core.storage.cell.IndexedStorageCellHandler;
import com.moakiee.thunderbolt.core.eject.ThunderboltBlockEntities;
import com.moakiee.thunderbolt.core.eject.EjectEndpointIndex;
import appeng.api.storage.StorageCells;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Entry point for Thunderbolt Core — the AE2 core optimization and feature layer.
 *
 * <p>It hosts low-level AE2 patches: most notably a linear-time autocrafting planner installed via
 * mixin on AE2's {@code CraftingCalculation}. It depends only on AE2, not on AE2 Lightning Tech, so
 * compatible host mods can register extended crafting CPU clusters without duplicating AE2 hooks.
 */
@Mod(ThunderboltCore.MODID)
public final class ThunderboltCore {

    public static final String MODID = "thunderbolt";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ThunderboltCore(IEventBus modEventBus, ModContainer container) {
        EjectCapabilityRegistry.installRuntime(EjectEndpointIndex.INSTANCE);
        ThunderboltBlockEntities.TYPES.register(modEventBus);
        // Engine selection: persist the chosen AE2 calculation engine (server config),
        // register Thunderbolt's own engine in the 附属名单, and expose the in-game switcher.
        CraftingEngineConfig.register(container);
        CraftingEngineRegistry.registerThunderbolt(new ThunderboltEngine());
        modEventBus.addListener(CraftingEngineNetwork::registerPayloads);
        modEventBus.addListener(this::onCommonSetup);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(CraftingEngineCommand::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        LOGGER.info("[Thunderbolt Core] initialized");
    }

    private void onPlayerLoggedIn(PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            CraftingEngineNetwork.syncTo(serverPlayer);
        }
    }

    private void onServerStarting(ServerStartingEvent event) {
        EjectEndpointIndex.INSTANCE.onServerStart(event.getServer());
        IndexedCellStorageRegistry.get(event.getServer());
        CraftingEngineConfig.seedFromConfig(); // load the persisted engine selection
    }

    private void onServerStopped(ServerStoppedEvent event) {
        EjectEndpointIndex.INSTANCE.onServerStop();
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> StorageCells.addCellHandler(IndexedStorageCellHandler.INSTANCE));
    }
}
