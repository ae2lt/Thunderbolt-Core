package com.moakiee.thunderbolt.ae2.crafting;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingPlan;
import appeng.me.service.CraftingService;
import java.util.Collection;
import java.util.Set;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

public interface ExtendedCraftingCpuCluster extends ICraftingCPU {
   boolean isActive();

   default boolean isFastPlanningEnabled() {
      return false;
   }

   Collection<? extends ICraftingCPU> getActiveCpus();

   long tickCraftingLogic(IEnergyService var1, CraftingService var2);

   void addWaitingKeys(Set<AEKey> var1);

   long insert(AEKey var1, long var2, Actionable var4);

   long getRequestedAmount(AEKey var1);

   ICraftingSubmitResult submitJob(IGrid var1, ICraftingPlan var2, IActionSource var3, @Nullable ICraftingRequester var4);

   default void prepareForCraftingService() {
   }

   default void restoreCraftingLinks(Consumer<CraftingLink> consumer) {
   }

   default boolean canAcceptPlan(ICraftingPlan plan) {
      return plan instanceof CraftingPlan;
   }

   default boolean canBeAutoSelectedFor(IActionSource source) {
      return switch (this.getSelectionMode()) {
         case ANY -> true;
         case PLAYER_ONLY -> source.player().isPresent();
         case MACHINE_ONLY -> source.player().isEmpty();
         default -> throw new IllegalStateException("Unsupported CPU selection mode: " + this.getSelectionMode());
      };
   }

   default boolean isPreferredFor(IActionSource source) {
      return switch (this.getSelectionMode()) {
         case ANY -> false;
         case PLAYER_ONLY -> source.player().isPresent();
         case MACHINE_ONLY -> source.player().isEmpty();
         default -> throw new IllegalStateException("Unsupported CPU selection mode: " + this.getSelectionMode());
      };
   }

   default boolean consumeCpuListChanged() {
      return false;
   }

   default boolean containsCpu(ICraftingCPU cpu) {
      if (cpu == this) {
         return true;
      } else {
         for (ICraftingCPU activeCpu : this.getActiveCpus()) {
            if (activeCpu == cpu) {
               return true;
            }
         }

         return false;
      }
   }
}
