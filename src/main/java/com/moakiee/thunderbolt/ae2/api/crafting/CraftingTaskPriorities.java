package com.moakiee.thunderbolt.ae2.api.crafting;

/** Stable comparator shared by TimeWheel schedulers and host-side tests. */
public final class CraftingTaskPriorities {
    public static int compare(Object left, Object right) {
        int leftPriority = left instanceof IPrioritizedCraftingTask prioritized
                ? prioritized.dispatchPriority() : 0;
        int rightPriority = right instanceof IPrioritizedCraftingTask prioritized
                ? prioritized.dispatchPriority() : 0;
        int compared = Integer.compare(rightPriority, leftPriority);
        if (compared != 0) return compared;
        int leftOrder = left instanceof IPrioritizedCraftingTask prioritized
                ? prioritized.dispatchOrder() : 0;
        int rightOrder = right instanceof IPrioritizedCraftingTask prioritized
                ? prioritized.dispatchOrder() : 0;
        return Integer.compare(leftOrder, rightOrder);
    }

    private CraftingTaskPriorities() { }
}
