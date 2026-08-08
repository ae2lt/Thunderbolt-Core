package com.moakiee.thunderbolt.ae2.overload.pattern;

import appeng.menu.guisync.PacketWritable;
import com.moakiee.thunderbolt.ae2.overload.model.EncodedOverloadPattern;
import com.moakiee.thunderbolt.ae2.overload.model.MatchMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;

public record OverloadPatternEditState(
   boolean hasSourcePattern,
   boolean sourceWasOverloadPattern,
   int inputCount,
   int outputCount,
   List<OverloadPatternEditState.ConfiguredSlot> inputSlots,
   List<OverloadPatternEditState.ConfiguredSlot> outputSlots,
   boolean canEncode
) implements PacketWritable {
   public OverloadPatternEditState(
      boolean hasSourcePattern,
      boolean sourceWasOverloadPattern,
      int inputCount,
      int outputCount,
      List<OverloadPatternEditState.ConfiguredSlot> inputSlots,
      List<OverloadPatternEditState.ConfiguredSlot> outputSlots,
      boolean canEncode
   ) {
      if (inputCount >= 0 && outputCount >= 0) {
         inputSlots = List.copyOf(Objects.requireNonNull(inputSlots, "inputSlots"));
         outputSlots = List.copyOf(Objects.requireNonNull(outputSlots, "outputSlots"));
         this.hasSourcePattern = hasSourcePattern;
         this.sourceWasOverloadPattern = sourceWasOverloadPattern;
         this.inputCount = inputCount;
         this.outputCount = outputCount;
         this.inputSlots = inputSlots;
         this.outputSlots = outputSlots;
         this.canEncode = canEncode;
      } else {
         throw new IllegalArgumentException("slot counts must be >= 0");
      }
   }

   public OverloadPatternEditState(FriendlyByteBuf buffer) {
      this(buffer.readBoolean(), buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt(), readSlots(buffer), readSlots(buffer), buffer.readBoolean());
   }

   public static OverloadPatternEditState empty() {
      return new OverloadPatternEditState(false, false, 0, 0, List.of(), List.of(), false);
   }

   public static OverloadPatternEditState fromPattern(
      ParsedPatternDefinition parsedPattern, EncodedOverloadPattern encodedPattern, boolean sourceWasOverloadPattern
   ) {
      Objects.requireNonNull(parsedPattern, "parsedPattern");
      Objects.requireNonNull(encodedPattern, "encodedPattern");
      ArrayList<OverloadPatternEditState.ConfiguredSlot> inputs = new ArrayList<>(parsedPattern.inputCount());

      for (ParsedPatternInput input : parsedPattern.inputs()) {
         inputs.add(new OverloadPatternEditState.ConfiguredSlot(input.slotIndex(), encodedPattern.inputModeOrDefault(input.slotIndex()), false));
      }

      ArrayList<OverloadPatternEditState.ConfiguredSlot> outputs = new ArrayList<>(parsedPattern.outputCount());

      for (ParsedPatternOutput output : parsedPattern.outputs()) {
         outputs.add(
            new OverloadPatternEditState.ConfiguredSlot(output.slotIndex(), encodedPattern.outputModeOrDefault(output.slotIndex()), output.primaryOutput())
         );
      }

      return new OverloadPatternEditState(true, sourceWasOverloadPattern, inputs.size(), outputs.size(), inputs, outputs, true);
   }

   public MatchMode inputMode(int slotIndex) {
      return this.inputSlots
         .stream()
         .filter(slot -> slot.slotIndex() == slotIndex)
         .findFirst()
         .map(OverloadPatternEditState.ConfiguredSlot::matchMode)
         .orElse(MatchMode.STRICT);
   }

   public MatchMode outputMode(int slotIndex) {
      return this.outputSlots
         .stream()
         .filter(slot -> slot.slotIndex() == slotIndex)
         .findFirst()
         .map(OverloadPatternEditState.ConfiguredSlot::matchMode)
         .orElse(MatchMode.STRICT);
   }

   public OverloadPatternEditState toggleInputMode(int slotIndex) {
      return new OverloadPatternEditState(
         this.hasSourcePattern,
         this.sourceWasOverloadPattern,
         this.inputCount,
         this.outputCount,
         toggle(this.inputSlots, slotIndex),
         this.outputSlots,
         this.canEncode
      );
   }

   public OverloadPatternEditState toggleOutputMode(int slotIndex) {
      return new OverloadPatternEditState(
         this.hasSourcePattern,
         this.sourceWasOverloadPattern,
         this.inputCount,
         this.outputCount,
         this.inputSlots,
         toggle(this.outputSlots, slotIndex),
         this.canEncode
      );
   }

   public EncodedOverloadPattern toEncodedPattern() {
      EncodedOverloadPattern.Builder builder = EncodedOverloadPattern.builder();

      for (OverloadPatternEditState.ConfiguredSlot slot : this.inputSlots) {
         builder.input(slot.slotIndex(), slot.matchMode());
      }

      for (OverloadPatternEditState.ConfiguredSlot slot : this.outputSlots) {
         builder.output(slot.slotIndex(), slot.matchMode());
      }

      return builder.build();
   }

   public void writeToPacket(FriendlyByteBuf buffer) {
      buffer.writeBoolean(this.hasSourcePattern);
      buffer.writeBoolean(this.sourceWasOverloadPattern);
      buffer.writeVarInt(this.inputCount);
      buffer.writeVarInt(this.outputCount);
      writeSlots(buffer, this.inputSlots);
      writeSlots(buffer, this.outputSlots);
      buffer.writeBoolean(this.canEncode);
   }

   private static List<OverloadPatternEditState.ConfiguredSlot> toggle(List<OverloadPatternEditState.ConfiguredSlot> slots, int slotIndex) {
      ArrayList<OverloadPatternEditState.ConfiguredSlot> updated = new ArrayList<>(slots.size());

      for (OverloadPatternEditState.ConfiguredSlot slot : slots) {
         if (slot.slotIndex() == slotIndex) {
            updated.add(slot.withMatchMode(nextMode(slot.matchMode())));
         } else {
            updated.add(slot);
         }
      }

      return List.copyOf(updated);
   }

   private static MatchMode nextMode(MatchMode matchMode) {
      return matchMode == MatchMode.STRICT ? MatchMode.ID_ONLY : MatchMode.STRICT;
   }

   private static void writeSlots(FriendlyByteBuf buffer, List<OverloadPatternEditState.ConfiguredSlot> slots) {
      buffer.writeVarInt(slots.size());

      for (OverloadPatternEditState.ConfiguredSlot slot : slots) {
         buffer.writeVarInt(slot.slotIndex());
         buffer.writeEnum(slot.matchMode());
         buffer.writeBoolean(slot.primaryOutput());
      }
   }

   private static List<OverloadPatternEditState.ConfiguredSlot> readSlots(FriendlyByteBuf buffer) {
      int size = buffer.readVarInt();
      ArrayList<OverloadPatternEditState.ConfiguredSlot> slots = new ArrayList<>(size);

      for (int i = 0; i < size; i++) {
         slots.add(new OverloadPatternEditState.ConfiguredSlot(buffer.readVarInt(), (MatchMode)buffer.readEnum(MatchMode.class), buffer.readBoolean()));
      }

      return List.copyOf(slots);
   }

   public static record ConfiguredSlot(int slotIndex, MatchMode matchMode, boolean primaryOutput) {
      public ConfiguredSlot(int slotIndex, MatchMode matchMode, boolean primaryOutput) {
         Objects.requireNonNull(matchMode, "matchMode");
         this.slotIndex = slotIndex;
         this.matchMode = matchMode;
         this.primaryOutput = primaryOutput;
      }

      public OverloadPatternEditState.ConfiguredSlot withMatchMode(MatchMode newMode) {
         return new OverloadPatternEditState.ConfiguredSlot(this.slotIndex, newMode, this.primaryOutput);
      }
   }
}
