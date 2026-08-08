package com.moakiee.thunderbolt.ae2.mixin;

import appeng.api.stacks.AEKeyType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(
   targets = {"net.pedroksl.advanced_ae.common.logic.ElapsedTimeTracker"},
   remap = false
)
public interface AaeElapsedTimeTrackerAccessor {
   @Invoker("addMaxItems")
   void invokeAddMaxItems(long var1, AEKeyType var3);
}
