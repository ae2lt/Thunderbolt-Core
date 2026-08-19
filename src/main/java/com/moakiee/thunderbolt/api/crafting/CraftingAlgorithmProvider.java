package com.moakiee.thunderbolt.api.crafting;

import appeng.api.networking.IGridNodeService;
import net.minecraft.resources.ResourceLocation;

/** Minimal node service: one provided algorithm, one current selection, and its player priority. */
public interface CraftingAlgorithmProvider extends IGridNodeService {
    /** The private algorithm owned by this node. This does not change with the GUI selection. */
    ResourceLocation getProvidedAlgorithm();

    ResourceLocation getSelectedAlgorithm();

    int getPriority();

    default boolean canSelectAlgorithm(ResourceLocation algorithmId) {
        return getProvidedAlgorithm().equals(algorithmId)
                || CraftingPlanningEngines.isPublic(algorithmId);
    }

    default CraftingAlgorithmSelection snapshot() {
        return new CraftingAlgorithmSelection(getSelectedAlgorithm(), getPriority());
    }
}
