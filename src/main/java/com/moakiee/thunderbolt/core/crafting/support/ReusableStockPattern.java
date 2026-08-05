package com.moakiee.thunderbolt.core.crafting.support;

import java.util.Map;

import appeng.api.stacks.AEKey;

import com.moakiee.thunderbolt.core.crafting.planner.ReusableStockSource;

/** Internal metadata consumed by the default planner for job-scoped reusable stock. */
public interface ReusableStockPattern {

    ReusableStockSource reusableStockSource();

    Map<AEKey, Long> reusableStockRequirements();

    default boolean acceptsReusableStockVariant(AEKey planned, AEKey actual) {
        return planned != null && planned.equals(actual);
    }

    default Map<AEKey, Long> availableReusableStockSnapshot() {
        return Map.of();
    }
}
