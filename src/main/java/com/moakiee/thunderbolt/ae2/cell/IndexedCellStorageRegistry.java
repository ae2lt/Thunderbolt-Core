package com.moakiee.thunderbolt.ae2.cell;

import com.moakiee.thunderbolt.internal.cell.IndexedCellStorageSavedData;
import java.util.UUID;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

public final class IndexedCellStorageRegistry {
   private final IndexedCellStorageSavedData data;

   private IndexedCellStorageRegistry(IndexedCellStorageSavedData data) {
      this.data = data;
   }

   public static IndexedCellStorageRegistry get(MinecraftServer server) {
      return new IndexedCellStorageRegistry(IndexedCellStorageSavedData.get(server));
   }

   @Nullable
   public static IndexedCellStorageRegistry getOrNull() {
      MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
      return server != null ? get(server) : null;
   }

   public IndexedStorage getOrCreateStorage(ResourceLocation type, UUID id, Provider registries) {
      return this.data.getOrCreateStorage(type, id, registries);
   }

   public void persistStorage(ResourceLocation type, UUID id, IndexedStorage storage, Provider registries) {
      this.data.persistStorage(type, id, storage, registries);
   }

   public void markStorageDirty(ResourceLocation type, UUID id, IndexedStorage storage) {
      this.data.markStorageDirty(type, id, storage);
   }

   public void removeCell(ResourceLocation type, UUID id) {
      this.data.removeCell(type, id);
   }
}
