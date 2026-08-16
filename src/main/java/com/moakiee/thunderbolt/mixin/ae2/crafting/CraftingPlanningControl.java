package com.moakiee.thunderbolt.mixin.ae2.crafting;

import java.util.List;

import appeng.api.networking.IGrid;

import com.moakiee.thunderbolt.api.crafting.PlanningChoice;

/** Private bridge between the two AE2 crafting mixins. */
interface CraftingPlanningControl {
    void thunderbolt$configurePlanning(List<PlanningChoice> candidates, IGrid grid);
}
