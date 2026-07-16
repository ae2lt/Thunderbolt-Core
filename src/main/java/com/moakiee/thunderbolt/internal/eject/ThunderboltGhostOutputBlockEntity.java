package com.moakiee.thunderbolt.internal.eject;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.moakiee.thunderbolt.registry.ThunderboltBlockEntities;

/** Runtime-only block entity used for endpoints whose real block position is empty. */
public final class ThunderboltGhostOutputBlockEntity extends BlockEntity {
    public ThunderboltGhostOutputBlockEntity(BlockPos pos) {
        super(ThunderboltBlockEntities.GHOST_OUTPUT.get(), pos, Blocks.AIR.defaultBlockState());
    }

    public ThunderboltGhostOutputBlockEntity(BlockPos pos, BlockState ignoredState) {
        this(pos);
    }
}
