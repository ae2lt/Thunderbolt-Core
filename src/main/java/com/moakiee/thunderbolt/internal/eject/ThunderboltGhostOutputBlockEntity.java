package com.moakiee.thunderbolt.internal.eject;

import com.moakiee.thunderbolt.registry.ThunderboltBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public final class ThunderboltGhostOutputBlockEntity extends BlockEntity {
   public ThunderboltGhostOutputBlockEntity(BlockPos pos) {
      super((BlockEntityType)ThunderboltBlockEntities.GHOST_OUTPUT.get(), pos, Blocks.AIR.defaultBlockState());
   }

   public ThunderboltGhostOutputBlockEntity(BlockPos pos, BlockState ignoredState) {
      this(pos);
   }
}
