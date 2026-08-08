package com.moakiee.thunderbolt.ae2.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.ElapsedTimeTracker;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ExecutingCraftingJob.class, remap = false)
public interface ExecutingCraftingJobAccessor {
   @Accessor("waitingFor")
   ListCraftingInventory getWaitingFor();

   @Accessor("timeTracker")
   ElapsedTimeTracker getTimeTracker();

   @Accessor("finalOutput")
   GenericStack getFinalOutput();

   @Accessor("remainingAmount")
   long getRemainingAmount();

   @Accessor("remainingAmount")
   void setRemainingAmount(long var1);

   @Accessor("link")
   CraftingLink getLink();

   @Accessor("tasks")
   Map<IPatternDetails, ?> getTasks();
}
