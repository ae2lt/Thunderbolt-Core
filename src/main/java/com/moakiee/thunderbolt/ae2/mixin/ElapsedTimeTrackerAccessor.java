package com.moakiee.thunderbolt.ae2.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import appeng.api.stacks.AEKeyType;
import appeng.crafting.execution.ElapsedTimeTracker;

// AE2 classes have no obfuscation mappings in the Forge dev environment — remap must be off.
@Mixin(value = ElapsedTimeTracker.class, remap = false)
public interface ElapsedTimeTrackerAccessor {
    @Invoker("decrementItems")
    void invokeDecrementItems(long amount, AEKeyType keyType);

    @Invoker("addMaxItems")
    void invokeAddMaxItems(long amount, AEKeyType keyType);
}
