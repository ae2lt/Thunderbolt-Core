package com.moakiee.thunderbolt.core.crafting.algorithm;

import java.util.List;

import appeng.api.networking.crafting.CalculationStrategy;

import com.moakiee.thunderbolt.api.crafting.PlanningChoice;

/** Internal bridge implemented by the CraftingCalculation mixin. */
public interface CraftingPlanningControl {
    void thunderbolt$configurePlanning(List<PlanningChoice> candidates, CalculationStrategy strategy);
}
