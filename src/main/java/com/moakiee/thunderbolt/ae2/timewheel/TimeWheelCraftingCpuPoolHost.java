package com.moakiee.thunderbolt.ae2.timewheel;

/**
 * State owner for a split time-wheel crafting CPU pool.
 *
 * <p>A host always owns a pool. Grid nodes that only publish another object's
 * pool should implement {@link TimeWheelCraftingCpuPoolProvider} instead.</p>
 */
public interface TimeWheelCraftingCpuPoolHost
        extends TimeWheelCraftingCpuHost, TimeWheelCraftingCpuPoolProvider {
    @Override
    TimeWheelCraftingCpuPool getTimeWheelCraftingCpuPool();
}
