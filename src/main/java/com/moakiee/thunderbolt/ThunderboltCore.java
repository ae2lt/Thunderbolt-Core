package com.moakiee.thunderbolt;

import appeng.api.storage.StorageCells;
import com.moakiee.thunderbolt.ae2.cell.IndexedCellStorageRegistry;
import com.moakiee.thunderbolt.ae2.cell.IndexedStorageCellHandler;
import com.moakiee.thunderbolt.api.eject.EjectCapabilityRegistry;
import com.moakiee.thunderbolt.registry.ThunderboltBlockEntities;
import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

@Mod("thunderbolt")
public final class ThunderboltCore {
   public static final String MODID = "thunderbolt";
   public static final Logger LOGGER = LogUtils.getLogger();

   public ThunderboltCore() {
      // Forge 1.20.1は@Modクラスを引数なしで生成するため、現在のMODイベントバスを明示的に取得する。
      this(FMLJavaModLoadingContext.get().getModEventBus());
   }

   private ThunderboltCore(IEventBus modEventBus) {
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
