package com.moakiee.thunderbolt.api.wireless;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public interface WirelessConnectionRef {
   ResourceKey<Level> dimension();

   BlockPos pos();

   Direction boundFace();

   CompoundTag toTag();

   default boolean sameTarget(ResourceKey<Level> otherDimension, BlockPos otherPos) {
      return this.dimension().equals(otherDimension) && this.pos().equals(otherPos);
   }
}
