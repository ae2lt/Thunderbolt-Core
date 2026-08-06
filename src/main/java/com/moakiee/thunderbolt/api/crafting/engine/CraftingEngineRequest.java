package com.moakiee.thunderbolt.api.crafting.engine;

import java.util.concurrent.Future;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import net.minecraft.world.level.Level;

/**
 * Immutable snapshot of one {@code CraftingService#beginCraftingCalculation} invocation handed to
 * the selected {@link CraftingEngine}.
 *
 * @param nativeInvoker lets the engine fall back to the original AE2 calculation path; the mixin
 *                      layer guards it against recursion
 */
public record CraftingEngineRequest(
        Level level,
        IGrid grid,
        ICraftingSimulationRequester requester,
        AEKey what,
        long amount,
        CalculationStrategy strategy,
        NativeCraftingInvoker nativeInvoker) {

    @FunctionalInterface
    public interface NativeCraftingInvoker {
        Future<ICraftingPlan> callNative(Level level,
                                         ICraftingSimulationRequester requester,
                                         AEKey what,
                                         long amount,
                                         CalculationStrategy strategy);
    }
}
