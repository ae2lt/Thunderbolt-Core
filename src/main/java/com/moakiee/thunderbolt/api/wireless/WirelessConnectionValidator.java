package com.moakiee.thunderbolt.api.wireless;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class WirelessConnectionValidator {
   private WirelessConnectionValidator() {
   }

   public static boolean shouldRunPeriodicPrune(ServerLevel level, BlockPos hostPos, int intervalTicks) {
      if (intervalTicks <= 0) {
         return false;
      } else {
         int offset = Math.floorMod(hostPos.asLong(), intervalTicks);
         return (level.getGameTime() + (long)offset) % (long)intervalTicks == 0L;
      }
   }

   public static WirelessConnectionValidator.Status validate(ServerLevel hostLevel, BlockPos hostPos, WirelessConnectionRef target, int maxDistance) {
      return validate(hostLevel, hostPos, target.dimension(), target.pos(), maxDistance);
   }

   public static WirelessConnectionValidator.Status validate(
      ServerLevel hostLevel, BlockPos hostPos, ResourceKey<Level> targetDimension, BlockPos targetPos, int maxDistance
   ) {
      if (!WirelessConnectionRange.isInRange(hostLevel.dimension(), hostPos, targetDimension, targetPos, maxDistance)) {
         return WirelessConnectionValidator.Status.REMOVE;
      } else {
         ServerLevel targetLevel = hostLevel.getServer().getLevel(targetDimension);
         if (targetLevel == null) {
            return WirelessConnectionValidator.Status.REMOVE;
         } else if (!targetLevel.isLoaded(targetPos)) {
            return WirelessConnectionValidator.Status.UNLOADED;
         } else {
            BlockState state = targetLevel.getBlockState(targetPos);
            return !state.isAir() && targetLevel.getBlockEntity(targetPos) != null
               ? WirelessConnectionValidator.Status.VALID
               : WirelessConnectionValidator.Status.REMOVE;
         }
      }
   }

   public static enum Status {
      VALID,
      UNLOADED,
      REMOVE;
   }
}
