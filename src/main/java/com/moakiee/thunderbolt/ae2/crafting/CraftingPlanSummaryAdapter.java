package com.moakiee.thunderbolt.ae2.crafting;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import it.unimi.dsi.fastutil.objects.Object2LongMap.Entry;
import java.util.Map;
import java.util.Objects;

public final class CraftingPlanSummaryAdapter {
   private CraftingPlanSummaryAdapter() {
   }

   public static CraftingPlan adapt(ICraftingPlan plan) {
      Objects.requireNonNull(plan, "plan");
      if (plan instanceof CraftingPlan) {
         return (CraftingPlan)plan;
      } else {
         return plan instanceof LoopCraftingPlan loopPlan
            ? loopPlan.delegate()
            : new CraftingPlan(
               plan.finalOutput(),
               plan.bytes(),
               plan.simulation(),
               plan.multiplePaths(),
               copyCounter(plan.usedItems()),
               copyCounter(plan.emittedItems()),
               copyCounter(plan.missingItems()),
               Map.copyOf(plan.patternTimes())
            );
      }
   }

   private static KeyCounter copyCounter(KeyCounter source) {
      Objects.requireNonNull(source, "plan counter");
      KeyCounter result = new KeyCounter();

      for (Entry<AEKey> entry : source) {
         result.add((AEKey)entry.getKey(), entry.getLongValue());
      }

      return result;
   }
}
