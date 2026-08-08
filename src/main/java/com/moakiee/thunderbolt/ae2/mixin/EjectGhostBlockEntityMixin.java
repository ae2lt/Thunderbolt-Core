package com.moakiee.thunderbolt.ae2.mixin;

import com.moakiee.thunderbolt.api.eject.EjectCapabilityRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({Level.class})
public abstract class EjectGhostBlockEntityMixin {
   @Inject(
      method = {"getBlockEntity"},
      at = {@At("RETURN")},
      cancellable = true
   )
   private void thunderbolt$injectGhostBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> callback) {
      if (callback.getReturnValue() == null && !EjectCapabilityRegistry.isEmpty() && (Object)this instanceof ServerLevel) {
         Level level = (Level)(Object)this;
         EjectCapabilityRegistry.Entry entry = EjectCapabilityRegistry.lookupAny(level.dimension(), pos.asLong());
         if (entry != null) {
            BlockEntity ghost = entry.ghostBlockEntity();
            if (ghost.getLevel() == null) {
               ghost.setLevel(level);
            }

            callback.setReturnValue(ghost);
         }
      }
   }
}
