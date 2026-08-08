package com.moakiee.thunderbolt.ae2.overload.pattern;

import com.moakiee.thunderbolt.ae2.overload.model.EncodedOverloadPattern;
import com.moakiee.thunderbolt.ae2.overload.model.MatchMode;
import com.moakiee.thunderbolt.ae2.overload.model.OverloadPatternSlot;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

public final class OverloadPatternPayloadTagCodec {
   private static final String TAG_HOST_KIND = "HostKind";
   private static final String TAG_SOURCE_PATTERN = "SourcePattern";
   private static final String TAG_RULES = "Rules";
   private static final String TAG_INPUTS = "Inputs";
   private static final String TAG_OUTPUTS = "Outputs";
   private static final String TAG_SLOT = "Slot";
   private static final String TAG_MODE = "Mode";

   private OverloadPatternPayloadTagCodec() {
   }

   public static CompoundTag writePayload(OverloadPatternPayload payload) {
      Objects.requireNonNull(payload, "payload");
      CompoundTag tag = new CompoundTag();
      tag.putString("HostKind", payload.requiredHostKind().name());
      tag.put("SourcePattern", payload.sourcePattern().toTag());
      tag.put("Rules", writeEncodedPattern(payload.encodedPattern()));
      return tag;
   }

   public static OverloadPatternPayload readPayload(CompoundTag tag) {
      Objects.requireNonNull(tag, "tag");
      PatternExecutionHostKind hostKind = PatternExecutionHostKind.valueOf(tag.getString("HostKind"));
      SourcePatternSnapshot sourcePattern = SourcePatternSnapshot.fromTag(tag.getCompound("SourcePattern"));
      EncodedOverloadPattern encodedPattern = readEncodedPattern(tag.getCompound("Rules"));
      return new OverloadPatternPayload(hostKind, sourcePattern, encodedPattern);
   }

   public static CompoundTag writeEncodedPattern(EncodedOverloadPattern encodedPattern) {
      Objects.requireNonNull(encodedPattern, "encodedPattern");
      CompoundTag tag = new CompoundTag();
      tag.put("Inputs", writeSlots(encodedPattern.inputSlots()));
      tag.put("Outputs", writeSlots(encodedPattern.outputSlots()));
      return tag;
   }

   public static EncodedOverloadPattern readEncodedPattern(CompoundTag tag) {
      Objects.requireNonNull(tag, "tag");
      EncodedOverloadPattern.Builder builder = EncodedOverloadPattern.builder();
      if (tag.contains("Inputs", 9)) {
         ListTag inputs = tag.getList("Inputs", 10);

         for (int i = 0; i < inputs.size(); i++) {
            CompoundTag slotTag = inputs.getCompound(i);
            builder.input(slotTag.getInt("Slot"), MatchMode.valueOf(slotTag.getString("Mode")));
         }
      }

      if (tag.contains("Outputs", 9)) {
         ListTag outputs = tag.getList("Outputs", 10);

         for (int i = 0; i < outputs.size(); i++) {
            CompoundTag slotTag = outputs.getCompound(i);
            builder.output(slotTag.getInt("Slot"), MatchMode.valueOf(slotTag.getString("Mode")));
         }
      }

      return builder.build();
   }

   private static ListTag writeSlots(Iterable<OverloadPatternSlot> slots) {
      ListTag list = new ListTag();

      for (OverloadPatternSlot slot : slots) {
         CompoundTag slotTag = new CompoundTag();
         slotTag.putInt("Slot", slot.slotIndex());
         slotTag.putString("Mode", slot.matchMode().name());
         list.add(slotTag);
      }

      return list;
   }
}
