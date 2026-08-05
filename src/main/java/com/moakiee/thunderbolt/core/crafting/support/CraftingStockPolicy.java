package com.moakiee.thunderbolt.core.crafting.support;

import java.util.Map;

import appeng.api.stacks.AEKey;

/** Internal host policy limiting pre-existing network stock visible to the default planner. */
public interface CraftingStockPolicy {

    long usablePreexistingStock(AEKey key, long snapshotAmount);

    default boolean groupsSecondaryVariants(AEKey key) {
        return false;
    }

    default long usablePreexistingStock(
            AEKey exactVariant, long exactAmount, Map<AEKey, Long> groupSnapshot) {
        return usablePreexistingStock(exactVariant, exactAmount);
    }
}
