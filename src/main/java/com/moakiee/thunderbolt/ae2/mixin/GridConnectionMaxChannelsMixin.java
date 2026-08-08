package com.moakiee.thunderbolt.ae2.mixin;

import appeng.api.networking.IGridNode;
import appeng.api.networking.pathing.ChannelMode;
import appeng.me.GridConnection;
import com.moakiee.thunderbolt.ae2.channel.BorrowedCapacityCalculator;
import com.moakiee.thunderbolt.ae2.channel.OverloadedChannelOwnerHelper;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GridConnection.class, remap = false)
public abstract class GridConnectionMaxChannelsMixin {
   @Shadow
   int usedChannels;

   @Inject(
      method = {"getMaxChannels"},
      at = {@At(value = "HEAD", remap = false)},
      remap = false,
      cancellable = true
   )
   private void ae2lt$use128ChannelsForOwnConnections(CallbackInfoReturnable<Integer> cir) {
      GridConnection self = (GridConnection)(Object)this;
      Object ownerA = OverloadedChannelOwnerHelper.tryGetOwner(self.a());
      Object ownerB = OverloadedChannelOwnerHelper.tryGetOwner(self.b());
      if (OverloadedChannelOwnerHelper.is128ChannelConnection(ownerA, ownerB)) {
         ChannelMode channelMode = self.b().getGrid().getPathingService().getChannelMode();
         if (channelMode != ChannelMode.INFINITE) {
            cir.setReturnValue(1073741823);
         }
      }
   }

   @Inject(
      method = {"propagateChannelsUpwards()I"},
      at = {@At(value = "HEAD", remap = false)},
      remap = false,
      cancellable = true
   )
   private void ae2lt$useFlowForConnectionPropagation(CallbackInfoReturnable<Integer> cir) {
      Reference2IntOpenHashMap<GridConnection> connFlow = BorrowedCapacityCalculator.activeConnectionFlow;
      if (connFlow != null) {
         Set<IGridNode> networkNodes = BorrowedCapacityCalculator.activeNetworkNodes;
         if (networkNodes != null) {
            GridConnection self = (GridConnection)(Object)this;
            if (networkNodes.contains(self.a()) || networkNodes.contains(self.b())) {
               int flow = connFlow.getInt(self);
               cir.setReturnValue(flow);
            }
         }
      }
   }
}
