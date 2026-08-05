package com.moakiee.thunderbolt.api.crafting.planner;

import java.util.List;

import appeng.api.stacks.AEKey;

/** Read-only inventory snapshot exposed to planners without leaking AE2 implementation classes. */
public interface CraftingInventoryView {

    long available(AEKey key);

    /** Concrete candidates currently visible for an AE2 fuzzy template. */
    default List<AEKey> fuzzyCandidates(AEKey template) {
        return List.of(template);
    }
}
