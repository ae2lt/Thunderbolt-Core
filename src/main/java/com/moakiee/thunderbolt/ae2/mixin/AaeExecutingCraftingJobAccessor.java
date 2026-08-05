package com.moakiee.thunderbolt.ae2.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

import appeng.api.crafting.IPatternDetails;
import appeng.crafting.CraftingLink;
import appeng.crafting.inv.ListCraftingInventory;

@Pseudo
@Mixin(targets = "net.pedroksl.advanced_ae.common.logic.ExecutingCraftingJob", remap = false)
public interface AaeExecutingCraftingJobAccessor {
    @Accessor("link")
    CraftingLink getLink();

    @Accessor("tasks")
    Map<IPatternDetails, ?> getTasks();

    @Accessor("waitingFor")
    ListCraftingInventory getWaitingFor();

    @Accessor("timeTracker")
    // AAE is not on the compile classpath; callers cast the result to AaeElapsedTimeTrackerAccessor.
    Object getTimeTracker();
}
