package com.moakiee.thunderbolt.ae2.timewheel;

import com.moakiee.thunderbolt.ae2.crafting.ExtendedCraftingCpuCluster;
import com.moakiee.thunderbolt.ae2.crafting.ExtendedCraftingCpuClusterHost;

/**
 * State owner for a split time-wheel crafting CPU pool.
 *
 * <p>A host always owns a pool. Grid nodes that only publish another object's
 * pool should implement {@link TimeWheelCraftingCpuPoolProvider} instead.</p>
 */
public interface TimeWheelCraftingCpuPoolHost
         extends TimeWheelCraftingCpuHost, ExtendedCraftingCpuClusterHost,
         TimeWheelCraftingCpuPoolProvider {
    TimeWheelCraftingCpuPool getTimeWheelCraftingCpuPool();

    @Override
    default ExtendedCraftingCpuCluster getExtendedCraftingCpuCluster() {
        return getTimeWheelCraftingCpuPool();
    }
}
