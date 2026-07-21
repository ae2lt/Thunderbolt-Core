package com.moakiee.thunderbolt.ae2.api.crafting;

/**
 * Controls how the AE2 crafting CPU accounts for a batch provider's copy budget.
 */
public enum BatchDispatchMode {
    /** Copy count is bounded by the crafting CPU's normal operation budget. */
    NORMAL,
    /**
     * Legacy accounting escape hatch: the provider may accept the CPU's remaining copy budget for
     * the cost of one successful operation. This never bypasses a finite CPU's per-tick copy budget.
     */
    UNBOUNDED
}
