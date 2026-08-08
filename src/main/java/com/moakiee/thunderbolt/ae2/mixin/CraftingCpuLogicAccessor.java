package com.moakiee.thunderbolt.ae2.mixin;

import appeng.api.stacks.AEKey;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.execution.CraftingCpuLogic;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = CraftingCpuLogic.class, remap = false)
public interface CraftingCpuLogicAccessor {
   @Accessor("job")
   @Nullable
   ExecutingCraftingJob getJob();

   @Invoker("finishJob")
   void invokeFinishJob(boolean var1);

   @Invoker("postChange")
   void invokePostChange(AEKey var1);
}
