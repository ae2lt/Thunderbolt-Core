package com.moakiee.thunderbolt.ae2.batch;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.crafting.inv.ListCraftingInventory;
import java.util.Iterator;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public interface BatchJobView {
   Iterator<BatchTaskHandle> taskIterator();

   ListCraftingInventory waitingFor();

   @Nullable
   default UUID craftingId() {
      return null;
   }

   default void insertWaitingFor(AEKey what, long amount) {
      this.waitingFor().insert(what, amount, Actionable.MODULATE);
   }

   void addContainerMaxItems(long var1, AEKeyType var3);
}
