package com.moakiee.thunderbolt.core.craft;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public final class CraftingCorePatternDispatcher {
   private final BooleanSupplier active;
   private final Predicate<IPatternDetails> loadedPattern;
   private final CraftingCorePatternDispatcher.BatchSink sink;

   public CraftingCorePatternDispatcher(BooleanSupplier active, Predicate<IPatternDetails> loadedPattern, CraftingCorePatternDispatcher.BatchSink sink) {
      this.active = Objects.requireNonNull(active);
      this.loadedPattern = Objects.requireNonNull(loadedPattern);
      this.sink = Objects.requireNonNull(sink);
   }

   public long pushBatch(IPatternDetails details, KeyCounter[] scaledInputs, long maxCraft) {
      if (maxCraft <= 0L) {
         return 0L;
      } else if (!this.active.getAsBoolean()) {
         return maxCraft;
      } else if (!this.loadedPattern.test(details)) {
         return maxCraft;
      } else {
         return !(details instanceof IMolecularAssemblerSupportedPattern) ? maxCraft : this.sink.pushBatch(details, scaledInputs, maxCraft);
      }
   }

   @FunctionalInterface
   public interface BatchSink {
      long pushBatch(IPatternDetails var1, KeyCounter[] var2, long var3);
   }
}
