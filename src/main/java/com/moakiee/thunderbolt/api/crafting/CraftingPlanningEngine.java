package com.moakiee.thunderbolt.api.crafting;

import appeng.api.networking.IGrid;
import net.minecraft.resources.ResourceLocation;

/** A server-side crafting-plan calculation implementation. */
public interface CraftingPlanningEngine {
    ResourceLocation id();

    /** Performs all runtime availability and request-support checks. */
    boolean check(IGrid grid, PlanningRequest request);

    /** Creates the state that is reused by every amount probe in one CraftingCalculation. */
    PlanningEngineSession createSession(IGrid grid, PlanningRequest request);
}
