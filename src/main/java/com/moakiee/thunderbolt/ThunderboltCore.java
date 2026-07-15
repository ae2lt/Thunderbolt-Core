package com.moakiee.thunderbolt;

import com.mojang.logging.LogUtils;
import com.moakiee.thunderbolt.api.eject.EjectCapabilityRegistry;
import com.moakiee.thunderbolt.ae2.cell.IndexedCellStorageRegistry;
import com.moakiee.thunderbolt.ae2.cell.IndexedStorageCellHandler;
import com.moakiee.thunderbolt.registry.ThunderboltBlockEntities;
import appeng.api.storage.StorageCells;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Entry point for Thunderbolt Core — the AE2 core optimization and feature layer.
 *
 * <p>It hosts low-level AE2 patches: most notably a linear-time autocrafting planner installed via
 * mixin on AE2's {@code CraftingCalculation}. It depends only on AE2, not on AE2 Lightning Tech, so
 * it can be used as a standalone AE2 crafting accelerator.
 */
@Mod(ThunderboltCore.MODID)
public final class ThunderboltCore {

    public static final String MODID = "thunderbolt";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ThunderboltCore(IEventBus modEventBus) {
        ThunderboltBlockEntities.TYPES.register(modEventBus);
        modEventBus.addListener(this::onCommonSetup);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        LOGGER.info("[Thunderbolt Core] initialized; fast-path autocrafting planner active={}",
                CoreConfig.FAST_PATH_ENABLED);
    }

    private void onServerStarting(ServerStartingEvent event) {
        EjectCapabilityRegistry.onServerStart(event.getServer());
        IndexedCellStorageRegistry.get(event.getServer());
    }

    private void onServerStopped(ServerStoppedEvent event) {
        EjectCapabilityRegistry.onServerStop();
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> StorageCells.addCellHandler(IndexedStorageCellHandler.INSTANCE));
    }
}
