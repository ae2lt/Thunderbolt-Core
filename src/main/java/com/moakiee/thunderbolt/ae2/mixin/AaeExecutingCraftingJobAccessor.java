package com.moakiee.thunderbolt.ae2.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.crafting.CraftingLink;
import appeng.crafting.inv.ListCraftingInventory;
import java.util.Map;
import net.pedroksl.advanced_ae.common.logic.ElapsedTimeTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(
   targets = {"net.pedroksl.advanced_ae.common.logic.ExecutingCraftingJob"},
   remap = false
)
public interface AaeExecutingCraftingJobAccessor {
   @Accessor("link")
   CraftingLink getLink();

   @Accessor("tasks")
   Map<IPatternDetails, ?> getTasks();

   @Accessor("waitingFor")
   ListCraftingInventory getWaitingFor();

   @Accessor("timeTracker")
   ElapsedTimeTracker getTimeTracker();
}
