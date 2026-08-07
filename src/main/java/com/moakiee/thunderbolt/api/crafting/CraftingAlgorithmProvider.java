package com.moakiee.thunderbolt.api.crafting;

import appeng.api.networking.IGridNodeService;
import net.minecraft.resources.ResourceLocation;

/** Minimal node service: one selected algorithm and its player-configured priority. */
public interface CraftingAlgorithmProvider extends IGridNodeService {
    ResourceLocation getSelectedAlgorithm();

    int getPriority();

    default CraftingAlgorithmSelection snapshot() {
        return new CraftingAlgorithmSelection(getSelectedAlgorithm(), getPriority());
    }
}
