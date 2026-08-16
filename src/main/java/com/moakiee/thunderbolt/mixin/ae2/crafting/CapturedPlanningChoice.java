package com.moakiee.thunderbolt.mixin.ae2.crafting;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngine;
import com.moakiee.thunderbolt.api.crafting.PlanningChoice;

/** A resolved choice and its Grid-thread-captured engine input. */
record CapturedPlanningChoice(
        PlanningChoice choice,
        @Nullable CraftingPlanningEngine engine,
        @Nullable Object capturedInput) {
    CapturedPlanningChoice {
        Objects.requireNonNull(choice, "choice");
        if ((choice.kind() == PlanningChoice.Kind.ENGINE) != (engine != null)) {
            throw new IllegalArgumentException("Only engine choices may carry an engine");
        }
    }

    static CapturedPlanningChoice vanilla() {
        return new CapturedPlanningChoice(PlanningChoice.VANILLA, null, null);
    }
}
