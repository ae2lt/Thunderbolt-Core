package com.moakiee.thunderbolt.api.crafting.planner;

/**
 * Stable terminal-planner SPI.
 *
 * <p>A planner either returns {@link CraftingPlannerStatus#UNSUPPORTED}, allowing the next
 * registered planner or AE2 fallback to run, or owns the entire request. Results are not decorated
 * or merged across planners.
 */
@FunctionalInterface
public interface ICraftingPlanner {

    CraftingPlannerResult plan(CraftingPlannerRequest request);
}
