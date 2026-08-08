package com.moakiee.thunderbolt.ae2.timewheel;

import com.moakiee.thunderbolt.ae2.crafting.ExtendedCraftingCpuCluster;
import com.moakiee.thunderbolt.ae2.crafting.ExtendedCraftingCpuClusterProvider;
import org.jetbrains.annotations.Nullable;

public interface TimeWheelCraftingCpuPoolProvider extends ExtendedCraftingCpuClusterProvider {
   @Nullable
   TimeWheelCraftingCpuPool getTimeWheelCraftingCpuPool();

   @Override
   default ExtendedCraftingCpuCluster getExtendedCraftingCpuCluster() {
      return this.getTimeWheelCraftingCpuPool();
   }
}
