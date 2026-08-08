package com.moakiee.thunderbolt.ae2.mixin;

import appeng.api.networking.IGridNode;
import appeng.blockentity.networking.ControllerBlockEntity;
import appeng.me.Grid;
import com.google.common.collect.SetMultimap;
import com.moakiee.thunderbolt.ae2.channel.ControllerMachineNodeLookup;
import java.util.Collection;
import java.util.Map;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Grid.class, remap = false)
public abstract class GridGetMachineNodesMixin {
   @Shadow
   @Final
   private SetMultimap<Class<?>, IGridNode> machines;

   @Inject(
      method = {"getMachineClasses"},
      at = {@At(value = "HEAD", remap = false)},
      remap = false,
      cancellable = true
   )
   private void ae2lt$normalizeControllerMachineClasses(CallbackInfoReturnable<Iterable<Class<?>>> cir) {
      Map<Class<?>, Collection<IGridNode>> machineMap = this.machines.asMap();
      if (ControllerMachineNodeLookup.hasOverloadedControllerNodes(machineMap)) {
         cir.setReturnValue(ControllerMachineNodeLookup.normalizedMachineClasses(machineMap));
      }
   }

   @Inject(
      method = {"getMachineNodes"},
      at = {@At(value = "HEAD", remap = false)},
      remap = false,
      cancellable = true
   )
   private void ae2lt$includeOverloadedControllersForControllerQueries(Class<?> machineClass, CallbackInfoReturnable<Iterable<IGridNode>> cir) {
      if (machineClass == ControllerBlockEntity.class) {
         Map<Class<?>, Collection<IGridNode>> machineMap = this.machines.asMap();
         if (ControllerMachineNodeLookup.hasOverloadedControllerNodes(machineMap)) {
            cir.setReturnValue(ControllerMachineNodeLookup.controllerNodes(machineMap));
         }
      }
   }
}
