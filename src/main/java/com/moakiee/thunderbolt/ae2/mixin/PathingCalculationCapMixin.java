package com.moakiee.thunderbolt.ae2.mixin;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridMultiblock;
import appeng.api.networking.IGridNode;
import appeng.api.networking.pathing.ChannelMode;
import appeng.blockentity.networking.ControllerBlockEntity;
import appeng.me.GridConnection;
import appeng.me.GridNode;
import appeng.me.pathfinding.IPathItem;
import appeng.me.pathfinding.PathingCalculation;
import com.moakiee.thunderbolt.ae2.channel.BorrowedCapacityCalculator;
import com.moakiee.thunderbolt.ae2.channel.ChannelProviderRegistry;
import com.moakiee.thunderbolt.ae2.channel.OverloadedChannelOwnerHelper;
import com.moakiee.thunderbolt.ae2.channel.OverloadedSubtreeNode;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2IntMap.Entry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PathingCalculation.class, remap = false)
public abstract class PathingCalculationCapMixin {
   @Shadow
   @Final
   private IGrid grid;
   @Shadow
   @Final
   private Set<IPathItem> visited;
   @Shadow
   @Final
   private Queue<IPathItem>[] queues;
   @Shadow
   @Final
   private Set<GridNode> channelNodes;
   @Shadow
   @Final
   private Set<GridNode> multiblocksWithChannel;
   @Unique
   private List<IGridNode> ae2lt$overloadedControllers;
   @Unique
   private boolean ae2lt$useMaxFlow;
   @Unique
   private BorrowedCapacityCalculator.Result ae2lt$flowResult;
   @Unique
   private int ae2lt$maxFlowChannelsInUse;

   @Inject(
      method = {"<init>"},
      at = {@At(value = "TAIL", remap = false)},
      remap = false
   )
   private void ae2lt$unifyOverloadedControllers(IGrid grid, CallbackInfo ci) {
      this.ae2lt$maxFlowChannelsInUse = -1;
      List<IGridNode> allControllers = OverloadedChannelOwnerHelper.getAllControllerNodes(grid);
      List<IGridNode> overloaded = new ArrayList<>();

      for (IGridNode node : allControllers) {
         if (ChannelProviderRegistry.isChannelProvider(node.getOwner())) {
            overloaded.add(node);
         }
      }

      this.ae2lt$overloadedControllers = overloaded;
      boolean hasControllers = !allControllers.isEmpty();
      ChannelMode channelMode = grid.getPathingService().getChannelMode();
      this.ae2lt$useMaxFlow = hasControllers && channelMode != ChannelMode.INFINITE;
      if (this.ae2lt$useMaxFlow && overloaded.size() > 1) {
         IGridNode source = overloaded.get(0);
         Set<IGridNode> nonSource = new ReferenceOpenHashSet(overloaded.subList(1, overloaded.size()));

         for (IGridNode nodex : nonSource) {
            if (nodex instanceof IPathItem p) {
               this.visited.remove(p);
            }
         }

         Queue<IPathItem> q0 = this.queues[0];
         ArrayDeque<IPathItem> keep = new ArrayDeque<>();

         while (!q0.isEmpty()) {
            IPathItem item = q0.poll();
            if (item instanceof GridConnection gc && (nonSource.contains(gc.a()) || nonSource.contains(gc.b()))) {
               this.visited.remove(gc);
               gc.setControllerRoute(null);
               continue;
            }

            keep.add(item);
         }

         q0.addAll(keep);
         Queue<IGridNode> bfs = new ArrayDeque<>();
         Set<IGridNode> bfsVisited = new ReferenceOpenHashSet();
         bfs.add(source);
         bfsVisited.add(source);

         while (!bfs.isEmpty()) {
            IGridNode cur = bfs.poll();

            for (IGridConnection conn : cur.getConnections()) {
               if (conn instanceof GridConnection) {
                  GridConnection gc = (GridConnection)conn;
                  IGridNode neighbor = gc.getOtherSide(cur);
                  if (bfsVisited.add(neighbor) && neighbor.getOwner() instanceof ControllerBlockEntity) {
                     if (nonSource.remove(neighbor) && !this.visited.contains(gc)) {
                        gc.setControllerRoute((IPathItem)cur);
                        this.visited.add(gc);
                        q0.add(gc);
                     }

                     bfs.add(neighbor);
                  }
               }
            }
         }
      }
   }

   @Inject(
      method = {"tryUseChannel"},
      at = {@At(value = "HEAD", remap = false)},
      remap = false,
      cancellable = true
   )
   private void ae2lt$skipAllDevices(GridNode node, CallbackInfoReturnable<Boolean> cir) {
      if (this.ae2lt$useMaxFlow) {
         cir.setReturnValue(false);
      }
   }

   @Inject(
      method = {"compute"},
      at = {@At(
         value = "INVOKE",
         target = "Lappeng/me/pathfinding/PathingCalculation;propagateAssignments()V",
         remap = false
      )},
      remap = false
   )
   private void ae2lt$runMaxFlowBeforeDFS(CallbackInfo ci) {
      BorrowedCapacityCalculator.clearActiveData();
      if (this.ae2lt$useMaxFlow) {
         this.ae2lt$flowResult = BorrowedCapacityCalculator.assignChannels(this.grid, this.ae2lt$overloadedControllers);
         if (this.ae2lt$flowResult != null) {
            this.channelNodes.addAll(this.ae2lt$flowResult.channelNodes());

            for (GridNode winner : this.ae2lt$flowResult.channelNodes()) {
               if (winner.hasFlag(GridFlags.MULTIBLOCK)) {
                  IGridMultiblock multiblock = (IGridMultiblock)winner.getService(IGridMultiblock.class);
                  if (multiblock != null) {
                     Iterator<IGridNode> siblings = multiblock.getMultiblockNodes();

                     while (siblings.hasNext()) {
                        IGridNode sibling = siblings.next();
                        if (sibling != null && sibling != winner) {
                           this.multiblocksWithChannel.add((GridNode)sibling);
                        }
                     }
                  }
               }
            }

            BorrowedCapacityCalculator.activeNodeFlow = this.ae2lt$flowResult.nodeFlow();
            BorrowedCapacityCalculator.activeNetworkNodes = this.ae2lt$flowResult.networkNodes();
            BorrowedCapacityCalculator.activeConnectionFlow = this.ae2lt$flowResult.connectionFlow();
         }
      }
   }

   @Inject(
      method = {"compute"},
      at = {@At(value = "TAIL", remap = false)},
      remap = false
   )
   private void ae2lt$applyFlowAndCleanup(CallbackInfo ci) {
      if (this.ae2lt$flowResult != null) {
         Set<GridConnection> resetSeen = new ReferenceOpenHashSet();
         Set<IGridNode> networkNodes = this.ae2lt$flowResult.networkNodes();

         for (IGridNode node : networkNodes) {
            for (IGridConnection conn : node.getConnections()) {
               if (conn instanceof GridConnection) {
                  GridConnection gc = (GridConnection)conn;
                  if (resetSeen.add(gc)) {
                     gc.setAdHocChannels(0);
                  }
               }
            }

            if (node instanceof OverloadedSubtreeNode osn) {
               osn.ae2lt$setUsedChannels(0);
            }
         }

         Reference2IntOpenHashMap<IGridNode> nodeFlow = this.ae2lt$flowResult.nodeFlow();

         for (IGridNode node : networkNodes) {
            if (node instanceof OverloadedSubtreeNode osn) {
               int flow = nodeFlow.getInt(node);
               osn.ae2lt$setUsedChannels(flow);
            }
         }

         for (GridNode sibling : this.multiblocksWithChannel) {
            if (networkNodes.contains(sibling)) {
               sibling.incrementChannelCount(1);
            }
         }

         Reference2IntOpenHashMap<GridConnection> connFlow = this.ae2lt$flowResult.connectionFlow();
         ObjectIterator var16 = connFlow.reference2IntEntrySet().iterator();

         while (var16.hasNext()) {
            Entry<GridConnection> entry = (Entry<GridConnection>)var16.next();
            ((GridConnection)entry.getKey()).setAdHocChannels(entry.getIntValue());
         }

         this.ae2lt$maxFlowChannelsInUse = this.ae2lt$flowResult.channelNodes().size();
      }

      BorrowedCapacityCalculator.clearActiveData();
      this.ae2lt$flowResult = null;
   }

   @Inject(
      method = {"getChannelsInUse"},
      at = {@At(value = "HEAD", remap = false)},
      remap = false,
      cancellable = true
   )
   private void ae2lt$overrideChannelsInUse(CallbackInfoReturnable<Integer> cir) {
      if (this.ae2lt$maxFlowChannelsInUse >= 0) {
         cir.setReturnValue(this.ae2lt$maxFlowChannelsInUse);
      }
   }
}
