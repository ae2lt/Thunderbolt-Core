package com.moakiee.thunderbolt.ae2.timewheel;

/**
 * Grid-node owner that exposes a split time-wheel crafting CPU pool.
 */
public interface TimeWheelCraftingCpuPoolHost extends TimeWheelCraftingCpuHost {
    TimeWheelCraftingCpuPool getTimeWheelCraftingCpuPool();
}
