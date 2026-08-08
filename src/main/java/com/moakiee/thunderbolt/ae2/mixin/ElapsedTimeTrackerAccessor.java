package com.moakiee.thunderbolt.ae2.mixin;

import appeng.api.stacks.AEKeyType;
import appeng.crafting.execution.ElapsedTimeTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = ElapsedTimeTracker.class, remap = false)
public interface ElapsedTimeTrackerAccessor {
   @Invoker("decrementItems")
   void invokeDecrementItems(long var1, AEKeyType var3);

   @Invoker("addMaxItems")
   void invokeAddMaxItems(long var1, AEKeyType var3);
}
