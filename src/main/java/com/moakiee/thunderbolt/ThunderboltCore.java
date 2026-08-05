package com.moakiee.thunderbolt;

import com.mojang.logging.LogUtils;
import com.moakiee.thunderbolt.api.eject.EjectCapabilityRegistry;
import com.moakiee.thunderbolt.ae2.cell.IndexedCellStorageRegistry;
import com.moakiee.thunderbolt.ae2.cell.IndexedStorageCellHandler;
import com.moakiee.thunderbolt.registry.ThunderboltBlockEntities;
import appeng.api.storage.StorageCells;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
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

    public ThunderboltCore() {
        var modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ThunderboltBlockEntities.TYPES.register(modEventBus);
        modEventBus.addListener(this::onCommonSetup);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarting);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopped);
        LOGGER.info("[Thunderbolt Core] initialized");
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
