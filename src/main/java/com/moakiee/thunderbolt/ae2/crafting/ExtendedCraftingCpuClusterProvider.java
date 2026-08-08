package com.moakiee.thunderbolt.ae2.crafting;

import appeng.api.networking.IGridNodeService;
import org.jetbrains.annotations.Nullable;

public interface ExtendedCraftingCpuClusterProvider extends IGridNodeService {
   @Nullable
   ExtendedCraftingCpuCluster getExtendedCraftingCpuCluster();
}
