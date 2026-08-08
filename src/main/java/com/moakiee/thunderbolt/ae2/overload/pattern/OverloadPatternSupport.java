package com.moakiee.thunderbolt.ae2.overload.pattern;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetails.IInput;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.world.item.ItemStack;

public final class OverloadPatternSupport {
   private OverloadPatternSupport() {
   }

   public static ParsedPatternDefinition toParsedDefinition(ItemStack sourcePatternStack, IPatternDetails sourceDetails, Provider registries) {
      Objects.requireNonNull(sourcePatternStack, "sourcePatternStack");
      Objects.requireNonNull(sourceDetails, "sourceDetails");
      Objects.requireNonNull(registries, "registries");
      ParsedPatternDefinition.Builder builder = ParsedPatternDefinition.builder(sourcePatternStack, registries);
      IInput[] inputs = sourceDetails.getInputs();

      for (int slot = 0; slot < inputs.length; slot++) {
         ItemStack inputTemplate = firstItemTemplate(inputs[slot].getPossibleInputs());
         if (!inputTemplate.isEmpty()) {
            builder.input(slot, inputTemplate);
         }
      }

      GenericStack[] outputs = sourceDetails.getOutputs();
      boolean primaryOutputAssigned = false;

      for (int slotx = 0; slotx < outputs.length; slotx++) {
         ItemStack outputStack = toItemStack(outputs[slotx]);
         if (!outputStack.isEmpty()) {
            builder.output(slotx, outputStack, !primaryOutputAssigned);
            primaryOutputAssigned = true;
         }
      }

      return builder.build();
   }

   public static ItemStack toItemStack(GenericStack stack) {
      Objects.requireNonNull(stack, "stack");
      return stack.what() instanceof AEItemKey itemKey ? itemKey.toStack((int)stack.amount()) : ItemStack.EMPTY;
   }

   private static ItemStack firstItemTemplate(GenericStack[] possibleInputs) {
      for (GenericStack possible : possibleInputs) {
         if (possible.what() instanceof AEItemKey itemKey) {
            return itemKey.toStack((int)possible.amount());
         }
      }

      return ItemStack.EMPTY;
   }
}
