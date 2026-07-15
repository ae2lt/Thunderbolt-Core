package com.moakiee.thunderbolt.ae2.timewheel;

import com.moakiee.thunderbolt.ae2.crafting.ExtendedCraftingCpuCluster;
import com.moakiee.thunderbolt.ae2.crafting.ExtendedCraftingCpuClusterHost;

/**
 * Grid-node owner that exposes a split time-wheel crafting CPU pool.
 */
public interface TimeWheelCraftingCpuPoolHost
        extends TimeWheelCraftingCpuHost, ExtendedCraftingCpuClusterHost {
    TimeWheelCraftingCpuPool getTimeWheelCraftingCpuPool();

    @Override
    default ExtendedCraftingCpuCluster getExtendedCraftingCpuCluster() {
        return getTimeWheelCraftingCpuPool();
    }
}
