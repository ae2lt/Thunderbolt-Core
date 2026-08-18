package com.moakiee.thunderbolt.ae2.crafting;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;

/** Internal bridge called by the crafting-service storage insertion hook. */
public interface ExtendedCraftingCpuInsertBridge {
    long thunderbolt$insertIntoExtendedCpus(AEKey what, long amount, Actionable mode);
}
