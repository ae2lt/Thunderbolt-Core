package com.vcwdfca.ae2ct.api;

import appeng.crafting.CraftingPlan;

public record RecipeHelper(CraftingPlan sourcePlan) {
    public static RecipeHelper fromCraftingPlan(CraftingPlan plan) {
        return new RecipeHelper(plan);
    }
}
