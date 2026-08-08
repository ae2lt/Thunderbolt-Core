package com.moakiee.thunderbolt.ae2.overload.pattern;

import java.util.Objects;
import net.minecraft.world.item.ItemStack;

public record ParsedPatternInput(int slotIndex, ItemStack stack) {
   public ParsedPatternInput(int slotIndex, ItemStack stack) {
      if (slotIndex < 0) {
         throw new IllegalArgumentException("slotIndex must be >= 0");
      } else {
         Objects.requireNonNull(stack, "stack");
         if (stack.isEmpty()) {
            throw new IllegalArgumentException("input stack must not be empty");
         } else {
            this.slotIndex = slotIndex;
            this.stack = stack;
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
