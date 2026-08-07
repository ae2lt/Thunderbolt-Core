package com.moakiee.thunderbolt.api.crafting;

import appeng.api.networking.crafting.ICraftingPlan;

/** Per-calculation engine state. A session is confined to the calculation thread. */
public interface PlanningEngineSession {
    PlanningAttempt attempt(long amount, boolean simulate);

    /** Allows an engine to attach plan-level execution metadata after AE2 finishes probing. */
    default ICraftingPlan finish(ICraftingPlan result) {
        return result;
    }
}
