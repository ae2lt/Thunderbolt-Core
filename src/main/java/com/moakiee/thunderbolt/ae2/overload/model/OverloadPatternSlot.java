package com.moakiee.thunderbolt.ae2.overload.model;

import java.util.Objects;

public record OverloadPatternSlot(OverloadPatternSlot.Side side, int slotIndex, MatchMode matchMode) {
   public OverloadPatternSlot(OverloadPatternSlot.Side side, int slotIndex, MatchMode matchMode) {
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(matchMode, "matchMode");
      if (slotIndex < 0) {
         throw new IllegalArgumentException("slotIndex must be >= 0");
      } else {
         this.side = side;
         this.slotIndex = slotIndex;
         this.matchMode = matchMode;
      }
   }

   public static OverloadPatternSlot input(int slotIndex, MatchMode matchMode) {
      return new OverloadPatternSlot(OverloadPatternSlot.Side.INPUT, slotIndex, matchMode);
   }

   public static OverloadPatternSlot output(int slotIndex, MatchMode matchMode) {
      return new OverloadPatternSlot(OverloadPatternSlot.Side.OUTPUT, slotIndex, matchMode);
   }

   public boolean isInput() {
      return this.side == OverloadPatternSlot.Side.INPUT;
   }

   public boolean isOutput() {
      return this.side == OverloadPatternSlot.Side.OUTPUT;
   }

   public static enum Side {
      INPUT,
      OUTPUT;
   }
}
