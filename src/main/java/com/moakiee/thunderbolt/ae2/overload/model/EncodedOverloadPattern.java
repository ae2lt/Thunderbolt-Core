package com.moakiee.thunderbolt.ae2.overload.model;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

public final class EncodedOverloadPattern {
   private static final EncodedOverloadPattern EMPTY = new EncodedOverloadPattern(List.of(), List.of());
   private final Map<Integer, OverloadPatternSlot> inputSlots;
   private final Map<Integer, OverloadPatternSlot> outputSlots;

   public EncodedOverloadPattern(Collection<OverloadPatternSlot> inputSlots, Collection<OverloadPatternSlot> outputSlots) {
      this.inputSlots = freezeByIndex(inputSlots, OverloadPatternSlot.Side.INPUT);
      this.outputSlots = freezeByIndex(outputSlots, OverloadPatternSlot.Side.OUTPUT);
   }

   public static EncodedOverloadPattern empty() {
      return EMPTY;
   }

   public static EncodedOverloadPattern.Builder builder() {
      return new EncodedOverloadPattern.Builder();
   }

   public Collection<OverloadPatternSlot> inputSlots() {
      return this.inputSlots.values();
   }

   public Collection<OverloadPatternSlot> outputSlots() {
      return this.outputSlots.values();
   }

   public Optional<OverloadPatternSlot> inputSlot(int slotIndex) {
      return Optional.ofNullable(this.inputSlots.get(slotIndex));
   }

   public Optional<OverloadPatternSlot> outputSlot(int slotIndex) {
      return Optional.ofNullable(this.outputSlots.get(slotIndex));
   }

   public MatchMode inputModeOrDefault(int slotIndex) {
      return this.inputSlot(slotIndex).map(OverloadPatternSlot::matchMode).orElse(MatchMode.STRICT);
   }

   public MatchMode outputModeOrDefault(int slotIndex) {
      return this.outputSlot(slotIndex).map(OverloadPatternSlot::matchMode).orElse(MatchMode.STRICT);
   }

   public boolean isEmpty() {
      return this.inputSlots.isEmpty() && this.outputSlots.isEmpty();
   }

   private static Map<Integer, OverloadPatternSlot> freezeByIndex(Collection<OverloadPatternSlot> slots, OverloadPatternSlot.Side expectedSide) {
      Objects.requireNonNull(slots, "slots");
      TreeMap<Integer, OverloadPatternSlot> sorted = new TreeMap<>();

      for (OverloadPatternSlot slot : slots) {
         Objects.requireNonNull(slot, "slot");
         if (slot.side() != expectedSide) {
            throw new IllegalArgumentException("slot side mismatch: expected " + expectedSide);
         }

         OverloadPatternSlot previous = sorted.put(slot.slotIndex(), slot);
         if (previous != null) {
            throw new IllegalArgumentException("duplicate slot index: " + slot.slotIndex());
         }
      }

      return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
   }

   public static final class Builder {
      private final Map<Integer, OverloadPatternSlot> inputSlots = new TreeMap<>();
      private final Map<Integer, OverloadPatternSlot> outputSlots = new TreeMap<>();

      private Builder() {
      }

      public EncodedOverloadPattern.Builder input(int slotIndex, MatchMode matchMode) {
         OverloadPatternSlot slot = OverloadPatternSlot.input(slotIndex, matchMode);
         putUnique(this.inputSlots, slot);
         return this;
      }

      public EncodedOverloadPattern.Builder output(int slotIndex, MatchMode matchMode) {
         OverloadPatternSlot slot = OverloadPatternSlot.output(slotIndex, matchMode);
         putUnique(this.outputSlots, slot);
         return this;
      }

      public EncodedOverloadPattern build() {
         return new EncodedOverloadPattern(this.inputSlots.values(), this.outputSlots.values());
      }

      private static void putUnique(Map<Integer, OverloadPatternSlot> target, OverloadPatternSlot slot) {
         OverloadPatternSlot previous = target.put(slot.slotIndex(), slot);
         if (previous != null) {
            throw new IllegalArgumentException("duplicate slot index: " + slot.slotIndex());
         }
      }
   }
}
