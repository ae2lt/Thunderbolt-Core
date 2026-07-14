package com.moakiee.thunderbolt.ae2.api.crafting;

import appeng.api.stacks.AEKey;
import java.util.Map;

/**
 * Marks an expanded task whose dispatched inputs remain part of a closed-loop seed reserve.
 * Values are physical seed units placed in flight by one ordinary pattern copy.
 */
public interface ISeedPreservingCraftingTask {
    Map<AEKey, Long> seedCreditPerCopy();
}
