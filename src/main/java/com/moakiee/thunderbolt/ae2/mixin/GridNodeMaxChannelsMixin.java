package com.moakiee.thunderbolt.ae2.mixin;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.api.networking.pathing.ChannelMode;
import appeng.blockentity.networking.ControllerBlockEntity;
import appeng.me.GridNode;
import appeng.me.pathfinding.IPathItem;
import com.moakiee.thunderbolt.ae2.channel.BorrowedCapacityCalculator;
import com.moakiee.thunderbolt.ae2.channel.ChannelProviderRegistry;
import com.moakiee.thunderbolt.ae2.channel.OverloadedChannelOwnerHelper;
import com.moakiee.thunderbolt.ae2.channel.OverloadedSubtreeNode;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GridNode.class, remap = false)
public abstract class GridNodeMaxChannelsMixin implements OverloadedSubtreeNode {
   @Shadow
   int usedChannels;
   @Unique
   private boolean ae2lt$inOverloadedSubtree;

   @Override
   public boolean ae2lt$isInOverloadedSubtree() {
      return this.ae2lt$inOverloadedSubtree;
   }

   @Override
   public void ae2lt$setUsedChannels(int channels) {
      this.usedChannels = channels;
   }

   @Inject(
      method = {"setControllerRoute"},
      at = {@At(value = "HEAD", remap = false)},
      remap = false
   )
   private void ae2lt$propagateSubtreeFlag(IPathItem route, CallbackInfo ci) {
      if (route.getControllerRoute() instanceof GridNode parent) {
         Object parentOwner = parent.getOwner();
         if (parentOwner instanceof ControllerBlockEntity) {
            this.ae2lt$inOverloadedSubtree = ChannelProviderRegistry.isChannelProvider(parentOwner);
         } else if (parent instanceof OverloadedSubtreeNode marker) {
            this.ae2lt$inOverloadedSubtree = marker.ae2lt$isInOverloadedSubtree();
         } else {
            this.ae2lt$inOverloadedSubtree = false;
         }
      } else {
         this.ae2lt$inOverloadedSubtree = false;
      }
   }

   @Inject(
      method = {"propagateChannelsUpwards"},
      at = {@At(value = "HEAD", remap = false)},
      remap = false,
      cancellable = true
   )
   private void ae2lt$useFlowForPropagation(boolean consumesChannel, CallbackInfoReturnable<Integer> cir) {
      Set<IGridNode> networkNodes = BorrowedCapacityCalculator.activeNetworkNodes;
      if (networkNodes != null) {
         IGridNode self = (GridNode)(Object)this;
         if (networkNodes.contains(self)) {
            Reference2IntOpenHashMap<IGridNode> nodeFlow = BorrowedCapacityCalculator.activeNodeFlow;
            int flow = nodeFlow.getInt(self);
            cir.setReturnValue(flow);
         }
      }
   }

   @Inject(
      method = {"getMaxChannels"},
      at = {@At(value = "HEAD", remap = false)},
      remap = false,
      cancellable = true
   )
   private void ae2lt$overloadedChannelCapacity(CallbackInfoReturnable<Integer> cir) {
      GridNode self = (GridNode)(Object)this;
      Object owner = OverloadedChannelOwnerHelper.tryGetOwner(self);
      if (OverloadedChannelOwnerHelper.is128ChannelOwner(owner)) {
         if (!self.hasFlag(GridFlags.CANNOT_CARRY)) {
            ChannelMode channelMode = self.getGrid().getPathingService().getChannelMode();
            if (channelMode != ChannelMode.INFINITE) {
               if (ChannelProviderRegistry.isChannelProvider(owner) || this.ae2lt$inOverloadedSubtree) {
                  cir.setReturnValue(1073741823);
               }
            }
         }
      }
   }
}
