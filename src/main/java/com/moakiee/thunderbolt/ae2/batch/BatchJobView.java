package com.moakiee.thunderbolt.ae2.batch;

import java.util.Iterator;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.crafting.inv.ListCraftingInventory;

public interface BatchJobView {
    Iterator<BatchTaskHandle> taskIterator();

    ListCraftingInventory waitingFor();

    /** Stable crafting ownership id for providers that persist accepted work. */
    default @Nullable UUID craftingId() {
        return null;
    }

    default void insertWaitingFor(AEKey what, long amount) {
        waitingFor().insert(what, amount, Actionable.MODULATE);
    }

    void addContainerMaxItems(long count, AEKeyType type);
}
