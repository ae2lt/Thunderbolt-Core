package com.moakiee.thunderbolt.ae2.cell;

import appeng.api.storage.cells.ICellHandler;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public final class IndexedStorageCellHandler implements ICellHandler {
   public static final IndexedStorageCellHandler INSTANCE = new IndexedStorageCellHandler();

   private IndexedStorageCellHandler() {
   }

   public boolean isCell(ItemStack stack) {
      return stack.getItem() instanceof IIndexedStorageCellItem;
   }

   @Nullable
   public StorageCell getCellInventory(ItemStack stack, @Nullable ISaveProvider host) {
      return stack.getItem() instanceof IIndexedStorageCellItem definition ? new IndexedStorageCellInventory(stack, definition, null, host) : null;
   }
}
