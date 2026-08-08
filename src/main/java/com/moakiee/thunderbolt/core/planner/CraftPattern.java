package com.moakiee.thunderbolt.core.planner;

import java.util.List;
import java.util.Objects;

public final class CraftPattern<K> {
   private final K output;
   private final long outputAmount;
   private final List<CraftInput<K>> inputs;
   private final List<CraftOutput<K>> byproducts;
   private final Object source;

   public CraftPattern(K output, long outputAmount, List<CraftInput<K>> inputs, Object source) {
      this(output, outputAmount, inputs, List.of(), source);
   }

   public CraftPattern(K output, long outputAmount, List<CraftInput<K>> inputs, List<CraftOutput<K>> byproducts, Object source) {
      this.output = Objects.requireNonNull(output, "output");
      if (outputAmount <= 0L) {
         throw new IllegalArgumentException("outputAmount must be > 0, was " + outputAmount);
      } else {
         this.outputAmount = outputAmount;
         this.inputs = List.copyOf(inputs);
         this.byproducts = List.copyOf(byproducts);
         this.source = source;
      }
   }

   public K output() {
      return this.output;
   }

   public long outputAmount() {
      return this.outputAmount;
   }

   public List<CraftInput<K>> inputs() {
      return this.inputs;
   }

   public List<CraftOutput<K>> byproducts() {
      return this.byproducts;
   }

   public Object source() {
      return this.source;
   }

   @Override
   public String toString() {
      return "CraftPattern[" + this.outputAmount + "x" + this.output + " <- " + this.inputs + "]";
   }
}
