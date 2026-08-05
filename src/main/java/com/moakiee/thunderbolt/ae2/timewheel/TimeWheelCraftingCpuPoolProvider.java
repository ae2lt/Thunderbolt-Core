package com.moakiee.thunderbolt.ae2.timewheel;

import org.jetbrains.annotations.Nullable;

import com.moakiee.thunderbolt.ae2.crafting.ExtendedCraftingCpuCluster;
import com.moakiee.thunderbolt.ae2.crafting.ExtendedCraftingCpuClusterProvider;

/**
 * Compatibility provider API for grid nodes that dynamically publish a time-wheel CPU pool.
 */
public interface TimeWheelCraftingCpuPoolProvider extends ExtendedCraftingCpuClusterProvider {
    @Nullable
    TimeWheelCraftingCpuPool getTimeWheelCraftingCpuPool();

    @Override
    default ExtendedCraftingCpuCluster getExtendedCraftingCpuCluster() {
        return getTimeWheelCraftingCpuPool();
    }
}
