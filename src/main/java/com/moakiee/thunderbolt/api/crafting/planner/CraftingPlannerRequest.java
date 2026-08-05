package com.moakiee.thunderbolt.api.crafting.planner;

import java.util.Objects;

import net.minecraft.world.level.Level;

import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;

/** Stable request passed to a Thunderbolt crafting planner. */
public record CraftingPlannerRequest(
        ICraftingService craftingService,
        ICraftingSimulationRequester requester,
        CraftingInventoryView inventory,
        Level level,
        AEKey output,
        long amount,
        boolean simulation) {

    public CraftingPlannerRequest {
        Objects.requireNonNull(craftingService, "craftingService");
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(output, "output");
        if (amount <= 0L) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
