package com.moakiee.thunderbolt.ae2.timewheel;

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
