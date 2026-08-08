package com.moakiee.thunderbolt.ae2.overload.cpu;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetails.IInput;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.moakiee.thunderbolt.ae2.overload.model.MatchMode;
import com.moakiee.thunderbolt.ae2.overload.pattern.OverloadPatternDetails;
import com.moakiee.thunderbolt.ae2.overload.pattern.OverloadedProviderOnlyPatternDetails;
import java.util.LinkedHashSet;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

public final class OverloadCpuInsertSupport {
   private OverloadCpuInsertSupport() {
   }

   public static long nativeStrictMatch(Object logic, AEKey incoming, long rawStrictMatch, long nativeExactWaiting) {
      Objects.requireNonNull(logic, "logic");
      Objects.requireNonNull(incoming, "incoming");
      long exactPending = OverloadCpuStateManager.INSTANCE.getRemainingForExactKey(logic, incoming);
      return nativeStrictMatch(rawStrictMatch, nativeExactWaiting, exactPending);
   }

   static long nativeStrictMatch(long rawStrictMatch, long nativeExactWaiting, long exactPending) {
      long normalizedStrict = Math.max(0L, rawStrictMatch);
      long strictExcess = Math.max(0L, Math.max(0L, nativeExactWaiting) - Math.max(0L, exactPending));
      return Math.min(normalizedStrict, strictExcess);
   }

   public static boolean hasPendingCollisionWithOrdinaryPattern(Object logic, IPatternDetails details) {
      Objects.requireNonNull(logic, "logic");
      Objects.requireNonNull(details, "details");
      OverloadCpuStateManager states = OverloadCpuStateManager.INSTANCE;
      if (!states.hasAnyPending(logic)) {
         return false;
      } else {
         for (GenericStack output : details.getOutputs()) {
            if (output.what() instanceof AEItemKey item && states.getRemainingForItem(logic, item.getId()) > 0L) {
               return true;
            }
         }

         for (IInput input : details.getInputs()) {
            for (GenericStack possible : input.getPossibleInputs()) {
               AEKey possibleKey = possible.what();
               if ((possibleKey != null ? input.getRemainingKey(possibleKey) : null) instanceof AEItemKey item
                  && states.getRemainingForItem(logic, item.getId()) > 0L) {
                  return true;
               }
            }
         }

         return false;
      }
   }

   public static boolean hasStrictCollisionWithOverloadPattern(
      Object logic, IPatternDetails details, OverloadedProviderOnlyPatternDetails overload, KeyCounter nativeWaiting
   ) {
      Objects.requireNonNull(logic, "logic");
      Objects.requireNonNull(details, "details");
      Objects.requireNonNull(overload, "overload");
      Objects.requireNonNull(nativeWaiting, "nativeWaiting");
      LinkedHashSet<ResourceLocation> idOnlyIds = new LinkedHashSet<>();
      LinkedHashSet<ResourceLocation> strictIds = new LinkedHashSet<>();

      for (OverloadPatternDetails.OutputSlot output : overload.overloadPatternDetailsView().outputs()) {
         AEItemKey key = AEItemKey.of(output.template());
         if (key == null) {
            return true;
         }

         (output.matchMode() == MatchMode.ID_ONLY ? idOnlyIds : strictIds).add(key.getId());
      }

      for (IInput input : details.getInputs()) {
         for (GenericStack possible : input.getPossibleInputs()) {
            AEKey possibleKey = possible.what();
            if ((possibleKey != null ? input.getRemainingKey(possibleKey) : null) instanceof AEItemKey item) {
               strictIds.add(item.getId());
            }
         }
      }

      OverloadCpuStateManager states = OverloadCpuStateManager.INSTANCE;

      for (ResourceLocation itemId : idOnlyIds) {
         if (strictIds.contains(itemId) || states.hasNativeStrictWaiting(logic, itemId, nativeWaiting)) {
            return true;
         }
      }

      return false;
   }
}
