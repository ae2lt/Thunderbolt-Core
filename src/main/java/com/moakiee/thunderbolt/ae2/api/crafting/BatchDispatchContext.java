package com.moakiee.thunderbolt.ae2.api.crafting;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;

/** Optional dispatch metadata for batch providers that need world or job ownership context. */
public record BatchDispatchContext(
        IPatternDetails details,
        KeyCounter[] oneCopyTemplate,
        long maxCraft,
        Level level,
        @Nullable UUID craftingJobId) {
}
