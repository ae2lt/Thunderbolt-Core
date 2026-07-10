package com.moakiee.thunderbolt.ae2.api.crafting;

/**
 * Controls how the AE2 crafting CPU accounts for a batch provider's copy budget.
 */
public enum BatchDispatchMode {
    /** Copy count is bounded by the crafting CPU's normal operation budget. */
    NORMAL,
    /** A provider may accept up to its reported capacity for the cost of one CPU operation. */
    UNBOUNDED
}
