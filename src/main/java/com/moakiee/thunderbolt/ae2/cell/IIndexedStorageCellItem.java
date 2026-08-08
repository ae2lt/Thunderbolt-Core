package com.moakiee.thunderbolt.ae2.cell;

import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import com.moakiee.thunderbolt.core.cell.ByteTracker;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

public interface IIndexedStorageCellItem {
   ResourceLocation storageType(ItemStack var1);

   default String cellIdTag(ItemStack stack) {
      return "thunderbolt:indexed_cell_id";
   }

   ByteTracker createByteTracker(ItemStack var1, IndexedStorage var2);

   double idleDrain(ItemStack var1);

   default boolean accepts(ItemStack stack, AEKey key, IActionSource source) {
      return true;
   }

   default boolean isPreferred(ItemStack stack, AEKey key, IndexedStorage storage, IActionSource source) {
      return storage.containsKey(key);
   }

   default void writeSummary(ItemStack stack, IndexedCellSummary summary) {
   }
}
