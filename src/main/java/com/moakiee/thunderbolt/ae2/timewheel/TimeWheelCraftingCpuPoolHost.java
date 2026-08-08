package com.moakiee.thunderbolt.ae2.timewheel;

import com.moakiee.thunderbolt.ae2.crafting.ExtendedCraftingCpuCluster;
import com.moakiee.thunderbolt.ae2.crafting.ExtendedCraftingCpuClusterHost;

public interface TimeWheelCraftingCpuPoolHost extends TimeWheelCraftingCpuHost, ExtendedCraftingCpuClusterHost, TimeWheelCraftingCpuPoolProvider {
   @Override
   TimeWheelCraftingCpuPool getTimeWheelCraftingCpuPool();

   @Override
   default ExtendedCraftingCpuCluster getExtendedCraftingCpuCluster() {
      return this.getTimeWheelCraftingCpuPool();
   }
}
