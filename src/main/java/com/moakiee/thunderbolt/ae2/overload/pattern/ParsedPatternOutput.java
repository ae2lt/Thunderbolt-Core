package com.moakiee.thunderbolt.ae2.overload.pattern;

import java.util.Objects;
import net.minecraft.world.item.ItemStack;

public record ParsedPatternOutput(int slotIndex, ItemStack stack, boolean primaryOutput) {
   public ParsedPatternOutput(int slotIndex, ItemStack stack, boolean primaryOutput) {
      if (slotIndex < 0) {
         throw new IllegalArgumentException("slotIndex must be >= 0");
      } else {
         Objects.requireNonNull(stack, "stack");
         if (stack.isEmpty()) {
            throw new IllegalArgumentException("output stack must not be empty");
         } else {
            this.slotIndex = slotIndex;
            this.stack = stack;
            this.primaryOutput = primaryOutput;
         }
      }
   }

   public ItemStack stack() {
      return this.stack.copy();
   }

   public int amountPerCraft() {
      return this.stack.getCount();
   }
}
