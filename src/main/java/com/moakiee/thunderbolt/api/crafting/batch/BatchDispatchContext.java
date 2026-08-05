package com.moakiee.thunderbolt.api.crafting.batch;

import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;

/**
 * Immutable metadata for one batch-provider dispatch.
 *
 * <p>{@link #oneCopyTemplate()} always represents exactly one pattern firing. The provider may
 * accept at most {@link #maxCraft()} copies and reports the unaccepted copy count.
 */
public record BatchDispatchContext(
        IPatternDetails details,
        KeyCounter[] oneCopyTemplate,
        long maxCraft,
        Level level,
        @Nullable UUID craftingJobId) {

    public BatchDispatchContext {
        Objects.requireNonNull(details, "details");
        oneCopyTemplate = Objects.requireNonNull(oneCopyTemplate, "oneCopyTemplate").clone();
        Objects.requireNonNull(level, "level");
        if (maxCraft < 0L) {
            throw new IllegalArgumentException("maxCraft must be non-negative");
        }
    }

    @Override
    public KeyCounter[] oneCopyTemplate() {
        return oneCopyTemplate.clone();
    }
}
