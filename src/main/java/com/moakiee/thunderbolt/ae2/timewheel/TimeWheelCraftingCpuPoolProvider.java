package com.moakiee.thunderbolt.ae2.timewheel;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.IGridNodeService;

/**
 * Grid-node service that dynamically exposes a time-wheel crafting CPU pool.
 *
 * <p>The provider may temporarily return {@code null}, for example while a
 * multiblock port is waiting for its controller to validate the structure.
 * The crafting service refreshes providers during the server tick and will
 * register or unregister the exposed pool when it changes.</p>
 */
public interface TimeWheelCraftingCpuPoolProvider extends IGridNodeService {
    @Nullable
    TimeWheelCraftingCpuPool getTimeWheelCraftingCpuPool();
}
