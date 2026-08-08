package com.moakiee.thunderbolt.api.wireless;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public final class WirelessConnectionLists {
   private WirelessConnectionLists() {
   }

   public static boolean isLocalDimension(@Nullable Level level, ResourceKey<Level> dimension) {
      return level == null || level.dimension().equals(dimension);
   }

   public static <T extends WirelessConnectionRef> int indexOf(List<T> source, ResourceKey<Level> dimension, BlockPos pos) {
      for (int i = 0; i < source.size(); i++) {
         if (source.get(i).sameTarget(dimension, pos)) {
            return i;
         }
      }

      return -1;
   }

   public static <T extends WirelessConnectionRef> boolean addOrReplace(List<T> source, T connection, int maxConnections) {
      int index = indexOf(source, connection.dimension(), connection.pos());
      if (index >= 0) {
         source.set(index, connection);
         return true;
      } else if (source.size() >= maxConnections) {
         return false;
      } else {
         source.add(connection);
         return true;
      }
   }

   public static <T extends WirelessConnectionRef> ListTag writeTagList(List<T> connections) {
      ListTag list = new ListTag();

      for (T connection : connections) {
         list.add(connection.toTag());
      }

      return list;
   }

   public static <T extends WirelessConnectionRef> void readTagList(
      CompoundTag data, String tagName, List<T> target, int maxConnections, WirelessConnectionLists.TagReader<T> reader
   ) {
      target.clear();
      if (data.contains(tagName, 9)) {
         ListTag list = data.getList(tagName, 10);

         for (int i = 0; i < list.size() && target.size() < maxConnections; i++) {
            target.add(reader.read(list.getCompound(i)));
         }
      }
   }

   public static <T extends WirelessConnectionRef> WirelessConnectionLists.PruneResult prune(
      List<T> connections, int cursor, int maxChecks, Predicate<T> shouldRemove
   ) {
      return prune(connections, cursor, maxChecks, shouldRemove, null);
   }

   public static <T extends WirelessConnectionRef> WirelessConnectionLists.PruneResult prune(
      List<T> connections, int cursor, int maxChecks, Predicate<T> shouldRemove, @Nullable Predicate<T> removalGuard
   ) {
      if (connections.isEmpty()) {
         return new WirelessConnectionLists.PruneResult(0, 0);
      } else if (maxChecks <= 0) {
         return new WirelessConnectionLists.PruneResult(0, Math.min(Math.max(cursor, 0), connections.size() - 1));
      } else {
         int checksRemaining = Math.min(maxChecks, connections.size());
         int removed = 0;
         int index = Math.min(Math.max(cursor, 0), connections.size() - 1);

         while (checksRemaining-- > 0 && !connections.isEmpty()) {
            if (index >= connections.size()) {
               index = 0;
            }

            T connection = (T)connections.get(index);
            if (!shouldRemove.test(connection) || removalGuard != null && !removalGuard.test(connection)) {
               index++;
            } else {
               connections.remove(index);
               removed++;
            }
         }

         return new WirelessConnectionLists.PruneResult(removed, connections.isEmpty() ? 0 : index % connections.size());
      }
   }

   public static record PruneResult(int removed, int nextCursor) {
   }

   @FunctionalInterface
   public interface TagReader<T extends WirelessConnectionRef> {
      T read(CompoundTag var1);
   }
}
