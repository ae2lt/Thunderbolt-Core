package com.moakiee.thunderbolt.ae2.api.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;
import java.util.UUID;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public record BatchDispatchContext(IPatternDetails details, KeyCounter[] oneCopyTemplate, long maxCraft, Level level, @Nullable UUID craftingJobId) {
}
