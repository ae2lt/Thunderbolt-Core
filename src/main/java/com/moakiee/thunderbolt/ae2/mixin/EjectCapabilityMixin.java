package com.moakiee.thunderbolt.ae2.mixin;

import com.moakiee.thunderbolt.api.eject.EjectCapabilityRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityProvider;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forge 1.20.1版のCapability入口へ、登録済みの搬出先を透過的に接続します。
 *
 * <p>NeoForge 1.21.1のBlockCapability自体をMixin対象にする方式はForgeには存在しないため、
 * 1.20.1のBlockEntity#getCapabilityへ同じ転送規則を移植しています。</p>
 */
@Mixin(value = CapabilityProvider.class, remap = false)
public abstract class EjectCapabilityMixin {
   @Unique
   private static final ThreadLocal<Boolean> THUNDERBOLT_PROXYING = ThreadLocal.withInitial(() -> false);

   @Inject(method = "getCapability", at = @At(value = "HEAD", remap = false), remap = false, cancellable = true)
   private <T> void thunderbolt$interceptEjectCapability(
         Capability<T> capability,
         Direction side,
         CallbackInfoReturnable<LazyOptional<T>> callback) {
      if (!((Object)this instanceof BlockEntity)) {
         return;
      }

      Level level = ((BlockEntity)(Object)this).getLevel();
      if (level == null || !(level instanceof ServerLevel)
            || side == null
            || EjectCapabilityRegistry.isEmpty()
            || THUNDERBOLT_PROXYING.get()
            || EjectCapabilityRegistry.isBypassed()) {
         return;
      }

      BlockEntity self = (BlockEntity)(Object)this;
      BlockPos pos = self.getBlockPos();
      EjectCapabilityRegistry.Entry entry = EjectCapabilityRegistry.lookupByFace(
            level.dimension(), pos.asLong(), side);
      if (entry == null) {
         return;
      }

      BlockEntity host = entry.getHost();
      if (host == null) {
         // 無効化された転送先は、対象Capabilityを空として扱います。
         if (capability == ForgeCapabilities.ITEM_HANDLER || capability == ForgeCapabilities.FLUID_HANDLER) {
            callback.setReturnValue(LazyOptional.empty());
         }
         return;
      }

      Level hostLevel = host.getLevel();
      if (hostLevel == null) {
         return;
      }

      // 転送先自身が再びこのMixinへ戻る無限再帰を防ぎます。
      THUNDERBOLT_PROXYING.set(true);
      try {
         LazyOptional<T> result = host.getCapability(capability, side);
         if (result.isPresent()) {
            callback.setReturnValue(result);
         }
      } finally {
         THUNDERBOLT_PROXYING.remove();
      }
   }
}
