package com.moakiee.thunderbolt.api.crafting;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.crafting.inv.NetworkCraftingSimulationState;
import net.minecraft.world.level.Level;

/** Immutable context passed to an engine's {@link CraftingPlanningEngine#check} method. */
public record PlanningRequest(
        Level level,
        ICraftingService craftingService,
        NetworkCraftingSimulationState networkInventory,
        AEKey output,
        long requestedAmount,
        CalculationStrategy strategy,
        ICraftingSimulationRequester requester,
        @Nullable Object context) {
}
