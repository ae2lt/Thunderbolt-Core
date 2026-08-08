package com.moakiee.thunderbolt.ae2.timewheel;

import appeng.api.config.Actionable;
import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public interface TimeWheelCraftingCpuHost {
   boolean isCpuActive();

   @Nullable
   IGrid getGrid();

   IActionSource getActionSource();

   @Nullable
   Level getLevel();

   void markCpuDirty();

   default long extractReusableSeed(AEKey key, long amount, Actionable mode) {
      return 0L;
   }

   default KeyCounter extractReusableSeedVariants(AEKey planned, long amount, Predicate<AEKey> acceptsVariant, Actionable mode) {
      KeyCounter result = new KeyCounter();
      if (planned != null && amount > 0L && acceptsVariant != null && acceptsVariant.test(planned)) {
         long extracted = this.extractReusableSeed(planned, amount, mode);
         if (extracted > 0L) {
            result.add(planned, extracted);
         }

         return result;
      } else {
         return result;
      }
   }

   default long insertReusableSeed(AEKey key, long amount, Actionable mode) {
      return 0L;
   }

   default CpuSelectionMode getSelectionMode() {
      return CpuSelectionMode.ANY;
   }

   Component getDisplayName();
}
