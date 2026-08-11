package com.moakiee.thunderbolt.internal.cell;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import com.moakiee.thunderbolt.ae2.cell.IndexedStorage;

/** Internal world persistence behind the public indexed-cell registry. */
public final class IndexedCellStorageSavedData extends SavedData {
    private static final String DATA_NAME = "thunderbolt_indexed_cells";
    private static final String LEGACY_DATA_NAME = "ae2lt_infinite_cells";
    private static final String TAG_STORES = "Stores";
    private static final String TAG_LEGACY_MIGRATION_COMPLETE = "LegacyMigrationComplete";
    private static final ResourceLocation LEGACY_AE2LT_TYPE =
            new ResourceLocation("ae2lt", "infinite_cell");

    private record StorageKey(ResourceLocation type, UUID id) {}

    private final Map<StorageKey, CompoundTag> cells = new HashMap<>();
    private final transient Map<StorageKey, IndexedStorage> storageCache = new HashMap<>();
    private boolean legacyMigrationComplete;

    public static IndexedCellStorageSavedData get(MinecraftServer server) {
        // Forge 1.20.1: no SavedData.Factory — computeIfAbsent(loader, factory, name).
        var data = server.overworld().getDataStorage().computeIfAbsent(
                IndexedCellStorageSavedData::load,
                IndexedCellStorageSavedData::new,
                DATA_NAME);
        data.migrateLegacyIfNeeded(server);
        return data;
    }

    public IndexedStorage getOrCreateStorage(
            ResourceLocation type,
            UUID id,
            HolderLookup.Provider registries) {
        var key = new StorageKey(type, id);
        var cached = storageCache.get(key);
        if (cached != null) return cached;
        var storage = new IndexedStorage();
        var encoded = cells.get(key);
        if (encoded != null) {
            storage.load(encoded, registries);
            if (storage.needsPersist()) {
                // The loader repaired malformed/duplicate entries and scheduled a full rewrite.
                setDirty();
            }
        }
        storageCache.put(key, storage);
        return storage;
    }

    public void persistStorage(
            ResourceLocation type,
            UUID id,
            IndexedStorage storage,
            HolderLookup.Provider registries) {
        if (storage == null) return;
        var key = new StorageKey(type, id);
        storageCache.put(key, storage);
        cells.put(key, storage.persist(cells.get(key), registries));
        setDirty();
    }

    public void markStorageDirty(ResourceLocation type, UUID id, IndexedStorage storage) {
        if (type == null || id == null || storage == null) return;
        storageCache.put(new StorageKey(type, id), storage);
        setDirty();
    }

    public void removeCell(ResourceLocation type, UUID id) {
        var key = new StorageKey(type, id);
        boolean changed = cells.remove(key) != null;
        storageCache.remove(key);
        if (changed) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        for (var entry : storageCache.entrySet()) {
            if (entry.getValue().needsPersist()) {
                // AE2 15.4.10 key serialization needs no HolderLookup.
                cells.put(entry.getKey(), entry.getValue().persist(cells.get(entry.getKey()), null));
            }
        }
        var storesTag = new CompoundTag();
        for (var entry : cells.entrySet()) {
            var typeTag = storesTag.getCompound(entry.getKey().type().toString());
            typeTag.put(entry.getKey().id().toString(), entry.getValue());
            storesTag.put(entry.getKey().type().toString(), typeTag);
        }
        tag.put(TAG_STORES, storesTag);
        tag.putBoolean(TAG_LEGACY_MIGRATION_COMPLETE, legacyMigrationComplete);
        return tag;
    }

    private void migrateLegacyIfNeeded(MinecraftServer server) {
        if (legacyMigrationComplete) return;
        var legacy = LegacyInfiniteCellSavedData.get(server);
        importLegacyCells(legacy.cells);
        legacyMigrationComplete = true;
        setDirty();
    }

    void importLegacyCells(Map<UUID, CompoundTag> legacyCells) {
        for (var entry : legacyCells.entrySet()) {
            cells.putIfAbsent(
                    new StorageKey(LEGACY_AE2LT_TYPE, entry.getKey()),
                    entry.getValue().copy());
        }
    }

    static Map<UUID, CompoundTag> decodeLegacyCells(CompoundTag tag) {
        var result = new HashMap<UUID, CompoundTag>();
        var cellsTag = tag.getCompound("cells");
        for (var idString : cellsTag.getAllKeys()) {
            try {
                result.put(UUID.fromString(idString), cellsTag.getCompound(idString).copy());
            } catch (IllegalArgumentException ignored) {}
        }
        return result;
    }

    static IndexedCellStorageSavedData load(CompoundTag tag) {
        var data = new IndexedCellStorageSavedData();
        data.legacyMigrationComplete = tag.getBoolean(TAG_LEGACY_MIGRATION_COMPLETE);
        var storesTag = tag.getCompound(TAG_STORES);
        for (var typeString : storesTag.getAllKeys()) {
            var type = ResourceLocation.tryParse(typeString);
            if (type == null) {
                continue;
            }
            var typeTag = storesTag.getCompound(typeString);
            for (var idString : typeTag.getAllKeys()) {
                try {
                    data.cells.put(
                            new StorageKey(type, UUID.fromString(idString)),
                            typeTag.getCompound(idString).copy());
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return data;
    }

    /** Read-only loader for the original AE2LT file. It is deliberately never marked dirty. */
    private static final class LegacyInfiniteCellSavedData extends SavedData {
        private final Map<UUID, CompoundTag> cells = new HashMap<>();

        private static LegacyInfiniteCellSavedData get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(
                    LegacyInfiniteCellSavedData::load,
                    LegacyInfiniteCellSavedData::new,
                    LEGACY_DATA_NAME);
        }

        private static LegacyInfiniteCellSavedData load(CompoundTag tag) {
            var data = new LegacyInfiniteCellSavedData();
            data.cells.putAll(decodeLegacyCells(tag));
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            var cellsTag = new CompoundTag();
            for (var entry : cells.entrySet()) {
                cellsTag.put(entry.getKey().toString(), entry.getValue());
            }
            tag.put("cells", cellsTag);
            return tag;
        }
    }
}
