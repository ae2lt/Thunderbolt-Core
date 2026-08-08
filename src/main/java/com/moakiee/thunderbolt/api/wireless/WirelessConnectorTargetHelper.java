package com.moakiee.thunderbolt.api.wireless;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class WirelessConnectorTargetHelper {
   private WirelessConnectorTargetHelper() {
   }

   public static Set<BlockPos> collectTargets(Level level, BlockPos origin, boolean contiguous) {
      return collectTargets(level, origin, contiguous, Integer.MAX_VALUE);
   }

   public static Set<BlockPos> collectTargets(Level level, BlockPos origin, boolean contiguous, int maxTargets) {
      if (maxTargets <= 0) {
         return Set.of();
      } else if (!contiguous) {
         return level.getBlockEntity(origin) != null ? Set.of(origin.immutable()) : Set.of();
      } else if (!level.isLoaded(origin)) {
         return Set.of();
      } else {
         BlockState originState = level.getBlockState(origin);
         BlockEntity originBlockEntity = level.getBlockEntity(origin);
         if (originBlockEntity == null) {
            return Set.of();
         } else {
            LinkedHashSet<BlockPos> visited = new LinkedHashSet<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(origin.immutable());

            while (!queue.isEmpty() && visited.size() < maxTargets) {
               BlockPos current = queue.removeFirst();
               if (visited.add(current) && visited.size() < maxTargets) {
                  for (Direction direction : Direction.values()) {
                     BlockPos next = current.relative(direction);
                     if (!visited.contains(next) && level.isLoaded(next)) {
                        BlockEntity nextBlockEntity = level.getBlockEntity(next);
                        if (nextBlockEntity != null
                           && nextBlockEntity.getClass() == originBlockEntity.getClass()
                           && level.getBlockState(next).is(originState.getBlock())) {
                           queue.addLast(next.immutable());
                        }
                     }
                  }
               }
            }

            return visited;
         }
      }
   }
}
