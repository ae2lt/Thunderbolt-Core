package com.moakiee.thunderbolt.ae2.overload.pattern;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.world.item.ItemStack;

public final class ParsedPatternDefinition {
   private final SourcePatternSnapshot sourcePattern;
   private final List<ParsedPatternInput> inputs;
   private final List<ParsedPatternOutput> outputs;

   public ParsedPatternDefinition(SourcePatternSnapshot sourcePattern, Collection<ParsedPatternInput> inputs, Collection<ParsedPatternOutput> outputs) {
      this.sourcePattern = Objects.requireNonNull(sourcePattern, "sourcePattern");
      this.inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
      this.outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
   }

   public static ParsedPatternDefinition.Builder builder(ItemStack sourcePatternStack, Provider registries) {
      return new ParsedPatternDefinition.Builder(SourcePatternSnapshot.fromItemStack(sourcePatternStack, registries));
   }

   public SourcePatternSnapshot sourcePattern() {
      return this.sourcePattern;
   }

   public List<ParsedPatternInput> inputs() {
      return this.inputs;
   }

   public List<ParsedPatternOutput> outputs() {
      return this.outputs;
   }

   public int inputCount() {
      return this.inputs.size();
   }

   public int outputCount() {
      return this.outputs.size();
   }

   public static final class Builder {
      private final SourcePatternSnapshot sourcePattern;
      private final List<ParsedPatternInput> inputs = new ArrayList<>();
      private final List<ParsedPatternOutput> outputs = new ArrayList<>();

      private Builder(SourcePatternSnapshot sourcePattern) {
         this.sourcePattern = sourcePattern;
      }

      public ParsedPatternDefinition.Builder input(int slotIndex, ItemStack stack) {
         this.inputs.add(new ParsedPatternInput(slotIndex, stack));
         return this;
      }

      public ParsedPatternDefinition.Builder output(int slotIndex, ItemStack stack, boolean primaryOutput) {
         this.outputs.add(new ParsedPatternOutput(slotIndex, stack, primaryOutput));
         return this;
      }

      public ParsedPatternDefinition build() {
         return new ParsedPatternDefinition(this.sourcePattern, this.inputs, this.outputs);
      }
   }
}
