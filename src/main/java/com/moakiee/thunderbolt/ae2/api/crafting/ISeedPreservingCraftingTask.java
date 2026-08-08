package com.moakiee.thunderbolt.ae2.api.crafting;

import appeng.api.stacks.AEKey;
import java.util.Set;
import java.util.UUID;

public interface ISeedPreservingCraftingTask {
   UUID reusableSeedGroupId();

   Set<AEKey> reusableSeedCycleKeys();

   boolean hasSingleSeedInputPerMember();
}
