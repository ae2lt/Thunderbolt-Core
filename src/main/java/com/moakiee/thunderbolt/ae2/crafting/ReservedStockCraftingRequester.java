package com.moakiee.thunderbolt.ae2.crafting;

import appeng.api.stacks.AEKey;
import java.util.Map;

public interface ReservedStockCraftingRequester {
   long usablePreexistingStock(AEKey var1, long var2);

   default boolean groupsSecondaryVariants(AEKey key) {
      return false;
   }

   default long usablePreexistingStock(AEKey exactVariant, long exactAmount, Map<AEKey, Long> groupSnapshot) {
      return this.usablePreexistingStock(exactVariant, exactAmount);
   }
}
