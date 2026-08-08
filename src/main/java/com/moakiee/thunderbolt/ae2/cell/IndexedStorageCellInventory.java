package com.moakiee.thunderbolt.ae2.cell;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import com.moakiee.thunderbolt.core.cell.ByteTracker;
import java.util.UUID;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

public final class IndexedStorageCellInventory implements StorageCell {
   private final ItemStack stack;
   private final IIndexedStorageCellItem definition;
   @Nullable
   private final Provider explicitRegistries;
   @Nullable
   private final ISaveProvider saveProvider;
   private final ResourceLocation storageType;
   private final String cellIdTag;
   private final IndexedStorage storage;
   private final ByteTracker byteTracker;
   @Nullable
   private UUID cellId;
   private long lastSyncModCount = -1L;
   private int lastWrittenTypes = -1;
   private long lastWrittenBytes = -1L;

   public IndexedStorageCellInventory(ItemStack stack, IIndexedStorageCellItem definition, @Nullable Provider registries, @Nullable ISaveProvider saveProvider) {
      this.stack = stack;
      this.definition = definition;
      this.explicitRegistries = registries;
      this.saveProvider = saveProvider;
      this.storageType = definition.storageType(stack);
      this.cellIdTag = definition.cellIdTag(stack);
      this.cellId = this.readCellId();
      IndexedCellStorageRegistry savedData = IndexedCellStorageRegistry.getOrNull();
      this.storage = this.cellId != null && savedData != null
         ? savedData.getOrCreateStorage(this.storageType, this.cellId, this.resolveRegistries())
         : new IndexedStorage();
      this.byteTracker = definition.createByteTracker(stack, this.storage);
      this.syncByteTracker();
   }

   public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
      if (amount > 0L && this.definition.accepts(this.stack, key, source)) {
         this.ensureSync();
         boolean newKey = !this.storage.containsKey(key);
         long accepted = Math.min(amount, this.byteTracker.computeMaxInsertable(key.getType(), newKey));
         if (accepted > 0L && mode != Actionable.SIMULATE) {
            this.storage.insert(key, accepted, Actionable.MODULATE);
            this.byteTracker.onInsert(key.getType(), accepted, newKey);
            this.lastSyncModCount = this.storage.getModCount();
            this.syncSummary();
            this.markChanged();
            return accepted;
         } else {
            return Math.max(accepted, 0L);
         }
      } else {
         return 0L;
      }
   }

   public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
      if (amount <= 0L) {
         return 0L;
      } else {
         this.ensureSync();
         if (mode == Actionable.SIMULATE) {
            return this.storage.extract(key, amount, Actionable.SIMULATE);
         } else {
            long extracted = this.storage.extract(key, amount, Actionable.MODULATE);
            if (extracted > 0L) {
               boolean keyRemoved = !this.storage.containsKey(key);
               this.byteTracker.onExtract(key.getType(), extracted, keyRemoved);
               this.lastSyncModCount = this.storage.getModCount();
               this.syncSummary();
               this.markChanged();
            }

            return extracted;
         }
      }
   }

   public void getAvailableStacks(KeyCounter out) {
      this.storage.getAvailableStacks(out);
   }

   public boolean isPreferredStorageFor(AEKey key, IActionSource source) {
      return this.definition.isPreferred(this.stack, key, this.storage, source);
   }

   public Component getDescription() {
      return this.stack.getHoverName();
   }

   public CellState getStatus() {
      this.ensureSync();
      if (this.storage.getTotalTypes() == 0) {
         return CellState.EMPTY;
      } else if (this.byteTracker.isFull()) {
         return CellState.FULL;
      } else {
         return this.byteTracker.isTypeFull() ? CellState.TYPES_FULL : CellState.NOT_EMPTY;
      }
   }

   public double getIdleDrain() {
      return this.definition.idleDrain(this.stack);
   }

   public boolean canFitInsideCell() {
      this.ensureSync();
      return this.storage.getTotalTypes() == 0;
   }

   public void persist() {
      IndexedCellStorageRegistry savedData = IndexedCellStorageRegistry.getOrNull();
      if (savedData != null) {
         if (this.storage.getTotalTypes() == 0) {
            if (this.storage.needsPersist()) {
               this.storage.persist(null, this.resolveRegistries());
            }

            if (this.cellId != null) {
               savedData.removeCell(this.storageType, this.cellId);
               this.clearCellId();
               this.cellId = null;
            }

            this.syncSummary();
         } else if (this.storage.needsPersist()) {
            this.ensureCellId();
            savedData.persistStorage(this.storageType, this.cellId, this.storage, this.resolveRegistries());
            this.ensureSync();
            this.syncSummary();
         }
      }
   }

   public IndexedStorage storage() {
      return this.storage;
   }

   public long usedBytes() {
      this.ensureSync();
      return this.byteTracker.getUsedBytes();
   }

   private void markChanged() {
      if (this.storage.getTotalTypes() == 0) {
         this.persist();
      } else {
         IndexedCellStorageRegistry savedData = IndexedCellStorageRegistry.getOrNull();
         if (savedData != null) {
            this.ensureCellId();
            savedData.markStorageDirty(this.storageType, this.cellId, this.storage);
         }
      }

      if (this.saveProvider != null) {
         this.saveProvider.saveChanges();
      }
   }

   private void ensureSync() {
      if (this.storage.getModCount() != this.lastSyncModCount) {
         this.syncByteTracker();
      }
   }

   private void syncByteTracker() {
      this.byteTracker.rebuild(this.storage.getTypeAmountLo(), this.storage.getTypeAmountHi(), this.storage.getTypeCounts(), this.storage.getTotalTypes());
      this.lastSyncModCount = this.storage.getModCount();
   }

   private void syncSummary() {
      int types = this.storage.getTotalTypes();
      long bytes = this.byteTracker.getUsedBytes();
      if (types != this.lastWrittenTypes || bytes != this.lastWrittenBytes) {
         this.lastWrittenTypes = types;
         this.lastWrittenBytes = bytes;
         this.definition.writeSummary(this.stack, new IndexedCellSummary(types, bytes));
      }
   }

   @Nullable
   private UUID readCellId() {
      // 1.20.1 uses the legacy item tag for the persistent cell UUID.
      CompoundTag tag = this.stack.getTag() == null ? new CompoundTag() : this.stack.getTag().copy();
      return tag.hasUUID(this.cellIdTag) ? tag.getUUID(this.cellIdTag) : null;
   }

   private void ensureCellId() {
      if (this.cellId == null) {
         this.cellId = UUID.randomUUID();
         UUID id = this.cellId;
         this.stack.getOrCreateTag().putUUID(this.cellIdTag, id);
      }
   }

   private void clearCellId() {
      if (this.stack.getTag() != null) {
         this.stack.getTag().remove(this.cellIdTag);
      }
   }

   private Provider resolveRegistries() {
      if (this.explicitRegistries != null) {
         return this.explicitRegistries;
      } else {
         MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
         if (server != null) {
            return server.registryAccess();
         } else {
            throw new IllegalStateException("No registries available for indexed cell persistence");
         }
      }
   }
}
