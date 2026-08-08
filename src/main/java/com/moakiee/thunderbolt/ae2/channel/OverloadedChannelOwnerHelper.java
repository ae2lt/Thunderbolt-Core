package com.moakiee.thunderbolt.ae2.channel;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridMultiblock;
import appeng.api.networking.IGridNode;
import appeng.api.networking.pathing.ChannelMode;
import appeng.blockentity.networking.ControllerBlockEntity;
import com.moakiee.thunderbolt.CoreConfig;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

public final class OverloadedChannelOwnerHelper {
   private static final Logger LOG = LogUtils.getLogger();
   private static final Set<String> LOGGED_OWNER_LOOKUP_FAILURES = Collections.synchronizedSet(new HashSet<>());

   private OverloadedChannelOwnerHelper() {
   }

   public static boolean is128ChannelOwner(@Nullable Object owner) {
      return owner instanceof OverloadedGridNodeOwner;
   }

   public static boolean is128ChannelConnection(@Nullable Object ownerA, @Nullable Object ownerB) {
      return is128ChannelOwner(ownerA) && is128ChannelOwner(ownerB);
   }

   public static int channelsPerController() {
      return CoreConfig.channelsPerController();
   }

   public static int supplyPerController(int cableCapacityFactor) {
      long supply = (long)channelsPerController() * (long)Math.max(1, cableCapacityFactor);
      return (int)Math.min(1073741823L, supply);
   }

   @Nullable
   public static Object tryGetOwner(IGridNode node) {
      try {
         return node.getOwner();
      } catch (RuntimeException var3) {
         String key = node.getClass().getName() + ":" + var3.getClass().getName();
         if (LOGGED_OWNER_LOOKUP_FAILURES.add(key)) {
            LOG.warn("AE2LT failed to read grid node owner from {}.", node.getClass().getName(), var3);
         }

         return null;
      }
   }

   public static List<IGridNode> getAllControllerNodes(IGrid grid) {
      List<IGridNode> all = new ArrayList<>();

      for (Class<?> clazz : grid.getMachineClasses()) {
         if (ControllerBlockEntity.class.isAssignableFrom(clazz)) {
            for (IGridNode node : grid.getMachineNodes(clazz)) {
               all.add(node);
            }
         }
      }

      return all;
   }

   public static int calculateOverloadedNetworkCapacity(IGrid grid) {
      int overloadedCount = 0;

      for (IGridNode node : getAllControllerNodes(grid)) {
         if (ChannelProviderRegistry.isChannelProvider(node.getOwner())) {
            overloadedCount++;
         }
      }

      if (overloadedCount == 0) {
         return 0;
      } else {
         ChannelMode channelMode = grid.getPathingService().getChannelMode();
         if (channelMode == ChannelMode.INFINITE) {
            return 0;
         } else {
            long capacity = (long)channelsPerController() * (long)overloadedCount * (long)channelMode.getCableCapacityFactor();
            return (int)Math.min(2147483647L, capacity);
         }
      }
   }

   public static int countUsedChannels(IGrid grid) {
      Set<IGridNode> visitedMultiblockSiblings = new ReferenceOpenHashSet();
      int count = 0;
      Iterator var3 = grid.getNodes().iterator();

      while (true) {
         label43:
         while (true) {
            if (!var3.hasNext()) {
               return count;
            }

            IGridNode node = (IGridNode)var3.next();
            if (node.hasFlag(GridFlags.REQUIRE_CHANNEL) && node.meetsChannelRequirements()) {
               if (!node.hasFlag(GridFlags.MULTIBLOCK)) {
                  break;
               }

               IGridMultiblock mb = (IGridMultiblock)node.getService(IGridMultiblock.class);
               if (mb == null) {
                  break;
               }

               if (visitedMultiblockSiblings.add(node)) {
                  Iterator<IGridNode> siblings = mb.getMultiblockNodes();

                  while (true) {
                     if (!siblings.hasNext()) {
                        break label43;
                     }

                     IGridNode sibling = siblings.next();
                     if (sibling != node) {
                        visitedMultiblockSiblings.add(sibling);
                     }
                  }
               }
            }
         }

         count++;
      }
   }
}
