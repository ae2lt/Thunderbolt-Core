package com.moakiee.thunderbolt.internal.cell;

import com.moakiee.thunderbolt.ae2.cell.IndexedStorage;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

public final class IndexedCellStorageSavedData extends SavedData {
   private static final String DATA_NAME = "thunderbolt_indexed_cells";
   private static final String LEGACY_DATA_NAME = "ae2lt_infinite_cells";
   private static final String TAG_STORES = "Stores";
   private static final String TAG_LEGACY_MIGRATION_COMPLETE = "LegacyMigrationComplete";
   private static final ResourceLocation LEGACY_AE2LT_TYPE = ResourceLocation.parse("ae2lt:infinite_cell");
   private final Map<IndexedCellStorageSavedData.StorageKey, CompoundTag> cells = new HashMap<>();
   private final transient Map<IndexedCellStorageSavedData.StorageKey, IndexedStorage> storageCache = new HashMap<>();
   private transient Provider registries;
   private boolean legacyMigrationComplete;

   public static IndexedCellStorageSavedData get(MinecraftServer server) {
      IndexedCellStorageSavedData data = server.overworld().getDataStorage().computeIfAbsent(
         IndexedCellStorageSavedData::load, IndexedCellStorageSavedData::new, "thunderbolt_indexed_cells"
      );
      data.migrateLegacyIfNeeded(server);
      return data;
   }

   public IndexedStorage getOrCreateStorage(ResourceLocation type, UUID id, Provider registries) {
      this.registries = registries;
      IndexedCellStorageSavedData.StorageKey key = new IndexedCellStorageSavedData.StorageKey(type, id);
      IndexedStorage cached = this.storageCache.get(key);
      if (cached != null) {
         return cached;
      } else {
         IndexedStorage storage = new IndexedStorage();
         CompoundTag encoded = this.cells.get(key);
         if (encoded != null) {
            storage.load(encoded, registries);
         }

         this.storageCache.put(key, storage);
         return storage;
      }
   }

   public void persistStorage(ResourceLocation type, UUID id, IndexedStorage storage, Provider registries) {
      if (storage != null) {
         this.registries = registries;
         IndexedCellStorageSavedData.StorageKey key = new IndexedCellStorageSavedData.StorageKey(type, id);
         this.storageCache.put(key, storage);
         this.cells.put(key, storage.persist(this.cells.get(key), registries));
         this.setDirty();
      }
   }

   public void markStorageDirty(ResourceLocation type, UUID id, IndexedStorage storage) {
      if (type != null && id != null && storage != null) {
         this.storageCache.put(new IndexedCellStorageSavedData.StorageKey(type, id), storage);
         this.setDirty();
      }
   }

   public void removeCell(ResourceLocation type, UUID id) {
      IndexedCellStorageSavedData.StorageKey key = new IndexedCellStorageSavedData.StorageKey(type, id);
      boolean changed = this.cells.remove(key) != null;
      this.storageCache.remove(key);
      if (changed) {
         this.setDirty();
      }
   }

   public CompoundTag save(CompoundTag tag) {
      for (Entry<IndexedCellStorageSavedData.StorageKey, IndexedStorage> entry : this.storageCache.entrySet()) {
         if (entry.getValue().needsPersist()) {
            this.cells.put(entry.getKey(), entry.getValue().persist(this.cells.get(entry.getKey()), this.registries));
         }
      }

      CompoundTag storesTag = new CompoundTag();

      for (Entry<IndexedCellStorageSavedData.StorageKey, CompoundTag> entryx : this.cells.entrySet()) {
         CompoundTag typeTag = storesTag.getCompound(entryx.getKey().type().toString());
         typeTag.put(entryx.getKey().id().toString(), (Tag)entryx.getValue());
         storesTag.put(entryx.getKey().type().toString(), typeTag);
      }

      tag.put("Stores", storesTag);
      tag.putBoolean("LegacyMigrationComplete", this.legacyMigrationComplete);
      return tag;
   }

   private void migrateLegacyIfNeeded(MinecraftServer server) {
      if (!this.legacyMigrationComplete) {
         IndexedCellStorageSavedData.LegacyInfiniteCellSavedData legacy = IndexedCellStorageSavedData.LegacyInfiniteCellSavedData.get(server);
         this.importLegacyCells(legacy.cells);
         this.legacyMigrationComplete = true;
         this.setDirty();
      }
   }

   void importLegacyCells(Map<UUID, CompoundTag> legacyCells) {
      for (Entry<UUID, CompoundTag> entry : legacyCells.entrySet()) {
         this.cells.putIfAbsent(new IndexedCellStorageSavedData.StorageKey(LEGACY_AE2LT_TYPE, entry.getKey()), entry.getValue().copy());
      }
   }

   static Map<UUID, CompoundTag> decodeLegacyCells(CompoundTag tag) {
      HashMap<UUID, CompoundTag> result = new HashMap<>();
      CompoundTag cellsTag = tag.getCompound("cells");

      for (String idString : cellsTag.getAllKeys()) {
         try {
            result.put(UUID.fromString(idString), cellsTag.getCompound(idString).copy());
         } catch (IllegalArgumentException var6) {
         }
      }

      return result;
   }

   private static IndexedCellStorageSavedData load(CompoundTag tag) {
      IndexedCellStorageSavedData data = new IndexedCellStorageSavedData();
      data.legacyMigrationComplete = tag.getBoolean("LegacyMigrationComplete");
      CompoundTag storesTag = tag.getCompound("Stores");

      for (String typeString : storesTag.getAllKeys()) {
         ResourceLocation type;
         try {
            type = ResourceLocation.parse(typeString);
         } catch (RuntimeException var12) {
            continue;
         }

         CompoundTag typeTag = storesTag.getCompound(typeString);

         for (String idString : typeTag.getAllKeys()) {
            try {
               data.cells.put(new IndexedCellStorageSavedData.StorageKey(type, UUID.fromString(idString)), typeTag.getCompound(idString));
            } catch (IllegalArgumentException var11) {
            }
         }
      }

      return data;
   }

   private static final class LegacyInfiniteCellSavedData extends SavedData {
      private final Map<UUID, CompoundTag> cells = new HashMap<>();

      private static IndexedCellStorageSavedData.LegacyInfiniteCellSavedData get(MinecraftServer server) {
         return server.overworld().getDataStorage().computeIfAbsent(
            IndexedCellStorageSavedData.LegacyInfiniteCellSavedData::load,
            IndexedCellStorageSavedData.LegacyInfiniteCellSavedData::new,
            "ae2lt_infinite_cells"
         );
      }

      private static IndexedCellStorageSavedData.LegacyInfiniteCellSavedData load(CompoundTag tag) {
         IndexedCellStorageSavedData.LegacyInfiniteCellSavedData data = new IndexedCellStorageSavedData.LegacyInfiniteCellSavedData();
         data.cells.putAll(IndexedCellStorageSavedData.decodeLegacyCells(tag));
         return data;
      }

      public CompoundTag save(CompoundTag tag) {
         CompoundTag cellsTag = new CompoundTag();

         for (Entry<UUID, CompoundTag> entry : this.cells.entrySet()) {
            cellsTag.put(entry.getKey().toString(), (Tag)entry.getValue());
         }

         tag.put("cells", cellsTag);
         return tag;
      }
   }

   private static record StorageKey(ResourceLocation type, UUID id) {
   }
}
