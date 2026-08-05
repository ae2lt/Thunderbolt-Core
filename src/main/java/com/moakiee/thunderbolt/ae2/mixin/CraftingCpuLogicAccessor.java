package com.moakiee.thunderbolt.ae2.mixin;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import appeng.api.stacks.AEKey;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;

// AE2 classes have no obfuscation mappings in the Forge dev environment — remap must be off.
@Mixin(value = CraftingCpuLogic.class, remap = false)
public interface CraftingCpuLogicAccessor {
    @Accessor("job")
    @Nullable
    ExecutingCraftingJob getJob();

    @Invoker("finishJob")
    void invokeFinishJob(boolean success);

    @Invoker("postChange")
    void invokePostChange(AEKey what);
}
