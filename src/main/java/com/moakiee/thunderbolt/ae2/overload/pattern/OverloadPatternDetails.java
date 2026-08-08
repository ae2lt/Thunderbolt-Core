package com.moakiee.thunderbolt.ae2.overload.pattern;

import com.moakiee.thunderbolt.ae2.overload.model.EncodedOverloadPattern;
import com.moakiee.thunderbolt.ae2.overload.model.MatchMode;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import net.minecraft.world.item.ItemStack;

public final class OverloadPatternDetails implements OverloadedProviderOnlyPatternDetails {
   private final SourcePatternSnapshot sourcePattern;
   private final EncodedOverloadPattern encodedPattern;
   private final List<OverloadPatternDetails.InputSlot> inputs;
   private final List<OverloadPatternDetails.OutputSlot> outputs;
   private String cachedIdentity;

   public OverloadPatternDetails(ParsedPatternDefinition parsedPattern, EncodedOverloadPattern encodedPattern) {
      Objects.requireNonNull(parsedPattern, "parsedPattern");
      this.sourcePattern = parsedPattern.sourcePattern();
      this.encodedPattern = Objects.requireNonNull(encodedPattern, "encodedPattern");
      this.inputs = parsedPattern.inputs().stream().map(input -> toInputSlot(input, encodedPattern.inputModeOrDefault(input.slotIndex()))).toList();
      this.outputs = parsedPattern.outputs().stream().map(output -> toOutputSlot(output, encodedPattern.outputModeOrDefault(output.slotIndex()))).toList();
   }

   @Override
   public PatternExecutionHostKind requiredHostKind() {
      return PatternExecutionHostKind.OVERLOADED_PATTERN_PROVIDER;
   }

   @Override
   public String overloadPatternIdentity() {
      String cached = this.cachedIdentity;
      if (cached != null) {
         return cached;
      } else {
         String computed = this.sourcePattern.itemId()
            + "#"
            + this.sourcePattern.fingerprint()
            + "|inputs="
            + this.encodedPattern.inputSlots().toString()
            + "|outputs="
            + this.encodedPattern.outputSlots().toString();
         this.cachedIdentity = computed;
         return computed;
      }
   }

   @Override
   public OverloadPatternDetails overloadPatternDetailsView() {
      return this;
   }

   public SourcePatternSnapshot sourcePattern() {
      return this.sourcePattern;
   }

   public EncodedOverloadPattern encodedPattern() {
      return this.encodedPattern;
   }

   public List<OverloadPatternDetails.InputSlot> inputs() {
      return this.inputs;
   }

   public List<OverloadPatternDetails.OutputSlot> outputs() {
      return this.outputs;
   }

   public List<OverloadPatternDetails.OutputSlot> primaryOutputs() {
      return this.outputs.stream().filter(OverloadPatternDetails.OutputSlot::primaryOutput).collect(Collectors.toUnmodifiableList());
   }

   public List<OverloadPatternDetails.OutputSlot> nonPrimaryOutputs() {
      return this.outputs.stream().filter(output -> !output.primaryOutput()).collect(Collectors.toUnmodifiableList());
   }

   public MatchMode inputMode(int slotIndex) {
      return this.encodedPattern.inputModeOrDefault(slotIndex);
   }

   public MatchMode outputMode(int slotIndex) {
      return this.encodedPattern.outputModeOrDefault(slotIndex);
   }

   private static OverloadPatternDetails.InputSlot toInputSlot(ParsedPatternInput input, MatchMode matchMode) {
      return new OverloadPatternDetails.InputSlot(input.slotIndex(), wipeIfIdOnly(normalizedCopy(input.stack()), matchMode), input.amountPerCraft(), matchMode);
   }

   private static OverloadPatternDetails.OutputSlot toOutputSlot(ParsedPatternOutput output, MatchMode matchMode) {
      return new OverloadPatternDetails.OutputSlot(
         output.slotIndex(), wipeIfIdOnly(normalizedCopy(output.stack()), matchMode), output.amountPerCraft(), matchMode, output.primaryOutput()
      );
   }

   private static ItemStack normalizedCopy(ItemStack stack) {
      ItemStack copy = stack.copy();
      copy.setCount(1);
      return copy;
   }

   private static ItemStack wipeIfIdOnly(ItemStack stack, MatchMode matchMode) {
      return matchMode.ignoresComponents() && stack.hasTag() && !stack.getTag().isEmpty()
         ? new ItemStack(stack.getItem(), stack.getCount())
         : stack;
   }

   public static record InputSlot(int slotIndex, ItemStack template, int amountPerCraft, MatchMode matchMode) {
      public InputSlot(int slotIndex, ItemStack template, int amountPerCraft, MatchMode matchMode) {
         Objects.requireNonNull(template, "template");
         if (amountPerCraft <= 0) {
            throw new IllegalArgumentException("amountPerCraft must be > 0");
         } else {
            Objects.requireNonNull(matchMode, "matchMode");
            template = OverloadPatternDetails.normalizedCopy(template);
            this.slotIndex = slotIndex;
            this.template = template;
            this.amountPerCraft = amountPerCraft;
            this.matchMode = matchMode;
         }
      }

      public ItemStack template() {
         return this.template.copy();
      }
   }

   public static record OutputSlot(int slotIndex, ItemStack template, int amountPerCraft, MatchMode matchMode, boolean primaryOutput) {
      public OutputSlot(int slotIndex, ItemStack template, int amountPerCraft, MatchMode matchMode, boolean primaryOutput) {
         Objects.requireNonNull(template, "template");
         if (amountPerCraft <= 0) {
            throw new IllegalArgumentException("amountPerCraft must be > 0");
         } else {
            Objects.requireNonNull(matchMode, "matchMode");
            template = OverloadPatternDetails.normalizedCopy(template);
            this.slotIndex = slotIndex;
            this.template = template;
            this.amountPerCraft = amountPerCraft;
            this.matchMode = matchMode;
            this.primaryOutput = primaryOutput;
         }
      }

      public ItemStack template() {
         return this.template.copy();
      }
   }
}
