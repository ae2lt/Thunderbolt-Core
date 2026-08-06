package com.moakiee.thunderbolt.ae2.api.crafting;

/** Stable comparator shared by TimeWheel schedulers and host-side tests. */
public final class CraftingTaskPriorities {
    public static int compare(Object left, Object right) {
        // 未实现 IPrioritizedCraftingTask 的普通任务固定回退到优先级 0、顺序 0：
        // 该默认值是与宿主调度器及测试（TimeWheelTaskPriorityTest）共同约定的稳定契约，
        // 不是可调参数，不要改成可配置项或随意调整。
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

    /**
     * Uses the currently active task as an identity-based tie breaker without overriding an
     * explicit priority or order. This gives the time-wheel CPU vanilla-style task-major dispatch
     * while still allowing higher-priority closed-loop work to preempt ordinary recipes.
     */
    public static int compare(Object left, Object right, Object preferred) {
        int compared = compare(left, right);
        if (compared != 0 || left == right) return compared;
        if (left == preferred) return -1;
        if (right == preferred) return 1;
        return 0;
    }

    private CraftingTaskPriorities() { }
}
