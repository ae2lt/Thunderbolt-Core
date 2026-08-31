package com.moakiee.thunderbolt.ae2.crafting;

import java.lang.reflect.InvocationTargetException;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.crafting.CraftingPlan;
import appeng.menu.me.crafting.CraftingPlanSummary;

/** Keeps AE2 Crafting Tree's optional summary payload initialized on Thunderbolt summaries. */
final class Ae2CraftingTreeSummaryBridge {
    private static final String[] API_PACKAGES = {
            // AE2: Crafting Tree 1.1.x
            "com.neuvillette.ae2ct.api",
            // AE2: Crafting Tree Refreshed 1.0.x
            "com.vcwdfca.ae2ct.api"
    };

    private Ae2CraftingTreeSummaryBridge() {
    }

    static void attach(CraftingPlanSummary summary, ICraftingPlan plan) {
        attachSupportedIntegration(summary, plan, CraftingPlanSummary.class.getClassLoader());
    }

    static boolean attachSupportedIntegration(Object summary, ICraftingPlan plan, ClassLoader loader) {
        for (String apiPackage : API_PACKAGES) {
            final Class<?> summaryView;
            final Class<?> recipeHelper;
            try {
                summaryView = Class.forName(apiPackage + ".ICraftingPlanSummary", false, loader);
                recipeHelper = Class.forName(apiPackage + ".RecipeHelper", false, loader);
            } catch (ClassNotFoundException absent) {
                continue;
            }
            if (!summaryView.isInstance(summary)) {
                continue;
            }

            try {
                Object tree = recipeHelper
                        .getMethod("fromCraftingPlan", CraftingPlan.class)
                        .invoke(null, CraftingPlanSummaryAdapter.adapt(plan));
                summaryView.getMethod("setJob", recipeHelper).invoke(summary, tree);
                return true;
            } catch (ReflectiveOperationException failure) {
                Throwable cause = failure instanceof InvocationTargetException invocation
                        ? invocation.getCause()
                        : failure;
                throw new IllegalStateException(
                        "Failed to attach AE2 Crafting Tree data to a Thunderbolt plan summary", cause);
            }
        }
        return false;
    }
}
