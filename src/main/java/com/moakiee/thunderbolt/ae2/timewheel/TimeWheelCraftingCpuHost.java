package com.moakiee.thunderbolt.ae2.timewheel;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;

public interface TimeWheelCraftingCpuHost {
    boolean isCpuActive();

    @Nullable
    IGrid getGrid();

    IActionSource getActionSource();

    @Nullable
    Level getLevel();

    void markCpuDirty();

    /** Extracts reusable seeds from host-owned storage before ME-network extraction. */
    default long extractReusableSeed(AEKey key, long amount, Actionable mode) {
        return 0L;
    }

    /** Inserts returned reusable seeds into host-owned storage before normal ME insertion. */
    default long insertReusableSeed(AEKey key, long amount, Actionable mode) {
        return 0L;
    }

    default CpuSelectionMode getSelectionMode() {
        return CpuSelectionMode.ANY;
    }

    /**
     * Display name shown for this CPU (e.g. in the crafting status menu). The concrete
     * multiblock supplies its own translatable name so the lib dispatch engine stays free
     * of any host-mod resource keys.
     */
    Component getDisplayName();
}
