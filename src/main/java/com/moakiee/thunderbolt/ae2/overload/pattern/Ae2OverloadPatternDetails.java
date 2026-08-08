package com.moakiee.thunderbolt.ae2.overload.pattern;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetails.IInput;
import appeng.api.crafting.IPatternDetails.PatternInputSink;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.pattern.AEProcessingPattern;
import com.moakiee.thunderbolt.ae2.overload.model.MatchMode;
import it.unimi.dsi.fastutil.objects.Object2LongMap.Entry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public final class Ae2OverloadPatternDetails implements IPatternDetails, OverloadedProviderOnlyPatternDetails, WrappedPatternDetails {
   private final AEItemKey definition;
   private final OverloadPatternDetails overloadDetails;
   private final IPatternDetails sourceDetails;
   private final IInput[] inputs;
   private final GenericStack[] outputs;

   public Ae2OverloadPatternDetails(AEItemKey definition, OverloadPatternDetails overloadDetails, IPatternDetails sourceDetails) {
      this.definition = Objects.requireNonNull(definition, "definition");
      this.overloadDetails = Objects.requireNonNull(overloadDetails, "overloadDetails");
      this.sourceDetails = Objects.requireNonNull(sourceDetails, "sourceDetails");
      IInput[] sourceInputs = sourceDetails.getInputs();
      this.inputs = new IInput[sourceInputs.length];

      for (int slot = 0; slot < sourceInputs.length; slot++) {
         this.inputs[slot] = wrapInput(sourceInputs[slot], overloadDetails.inputMode(slot));
      }

      this.outputs = wipeIdOnlyOutputs(sourceDetails.getOutputs(), overloadDetails);
   }

   private static GenericStack[] wipeIdOnlyOutputs(GenericStack[] sourceOutputs, OverloadPatternDetails overloadDetails) {
      GenericStack[] result = new GenericStack[sourceOutputs.length];

      for (int slot = 0; slot < sourceOutputs.length; slot++) {
         result[slot] = wipeIfIdOnly(sourceOutputs[slot], overloadDetails.outputMode(slot));
      }

      return result;
   }

   private static GenericStack wipeIfIdOnly(GenericStack stack, MatchMode matchMode) {
      return matchMode.ignoresComponents() && stack.what() instanceof AEItemKey itemKey && itemKey.hasTag()
         ? new GenericStack(AEItemKey.of(itemKey.getItem()), stack.amount())
         : stack;
   }

   public AEItemKey getDefinition() {
      return this.definition;
   }

   public IInput[] getInputs() {
      return (IInput[])this.inputs.clone();
   }

   public GenericStack[] getOutputs() {
      return this.outputs;
   }

   public boolean supportsPushInputsToExternalInventory() {
      return this.sourceDetails.supportsPushInputsToExternalInventory();
   }

   public void pushInputsToExternalInventory(KeyCounter[] inputHolder, PatternInputSink inputSink) {
      if (this.sourceDetails instanceof AEProcessingPattern processingPattern) {
         this.pushProcessingInputsToExternalInventory(processingPattern, inputHolder, inputSink);
      } else {
         this.sourceDetails.pushInputsToExternalInventory(inputHolder, inputSink);
      }
   }

   @Override
   public PatternExecutionHostKind requiredHostKind() {
      return PatternExecutionHostKind.OVERLOADED_PATTERN_PROVIDER;
   }

   @Override
   public String overloadPatternIdentity() {
      return this.overloadDetails.overloadPatternIdentity();
   }

   @Override
   public OverloadPatternDetails overloadPatternDetailsView() {
      return this.overloadDetails;
   }

   @Override
   public IPatternDetails wrappedPatternDetails() {
      return this.sourceDetails;
   }

   @Override
   public boolean equals(Object obj) {
      if (obj instanceof Ae2OverloadPatternDetails other && this.definition.equals(other.definition)) {
         return true;
      }

      return false;
   }

   @Override
   public int hashCode() {
      return this.definition.hashCode();
   }

   private void pushProcessingInputsToExternalInventory(AEProcessingPattern processingPattern, KeyCounter[] inputHolder, PatternInputSink inputSink) {
      KeyCounter availableInputs = new KeyCounter();

      for (KeyCounter counter : inputHolder) {
         availableInputs.addAll(counter);
      }

      for (GenericStack sparseInput : processingPattern.getSparseInputs()) {
         if (sparseInput != null) {
            AEKey expectedKey = sparseInput.what();
            long requiredAmount = sparseInput.amount();
            MatchMode matchMode = this.resolveMatchMode(expectedKey);
            if (matchMode == MatchMode.ID_ONLY && expectedKey instanceof AEItemKey expectedItemKey) {
               pushIdOnlyInput(expectedItemKey, requiredAmount, availableInputs, inputSink);
            } else {
               pushStrictInput(expectedKey, requiredAmount, availableInputs, inputSink);
            }
         }
      }
   }

   private MatchMode resolveMatchMode(AEKey expectedKey) {
      IInput[] sourceInputs = this.sourceDetails.getInputs();

      for (int slot = 0; slot < sourceInputs.length; slot++) {
         for (GenericStack possibleInput : sourceInputs[slot].getPossibleInputs()) {
            if (possibleInput.what().equals(expectedKey)) {
               return this.overloadDetails.inputMode(slot);
            }
         }
      }

      return MatchMode.STRICT;
   }

   private static IInput wrapInput(IInput sourceInput, MatchMode matchMode) {
      return (IInput)(matchMode == MatchMode.ID_ONLY && hasItemVariants(sourceInput.getPossibleInputs())
         ? new Ae2OverloadPatternDetails.OverloadInput(sourceInput, matchMode)
         : sourceInput);
   }

   private static boolean hasItemVariants(GenericStack[] possibleInputs) {
      for (GenericStack possibleInput : possibleInputs) {
         if (possibleInput.what() instanceof AEItemKey) {
            return true;
         }
      }

      return false;
   }

   private static void pushStrictInput(AEKey expectedKey, long requiredAmount, KeyCounter availableInputs, PatternInputSink inputSink) {
      long available = availableInputs.get(expectedKey);
      if (available < requiredAmount) {
         throw new RuntimeException("Expected at least %d of %s when pushing pattern, but only %d available".formatted(requiredAmount, expectedKey, available));
      } else {
         inputSink.pushInput(expectedKey, requiredAmount);
         availableInputs.remove(expectedKey, requiredAmount);
      }
   }

   private static void pushIdOnlyInput(AEItemKey expectedItemKey, long requiredAmount, KeyCounter availableInputs, PatternInputSink inputSink) {
      long remaining = requiredAmount - pushMatchingKey(expectedItemKey, requiredAmount, availableInputs, inputSink);
      if (remaining > 0L) {
         for (AEKey availableKey : snapshotKeys(availableInputs)) {
            if (remaining <= 0L) {
               break;
            }

            if (!availableKey.equals(expectedItemKey)
               && availableKey instanceof AEItemKey availableItemKey
               && availableItemKey.getItem() == expectedItemKey.getItem()) {
               remaining -= pushMatchingKey(availableKey, remaining, availableInputs, inputSink);
            }
         }
      }

      if (remaining > 0L) {
         throw new RuntimeException(
            "Expected at least %d of %s by item id when pushing pattern, but only %d available"
               .formatted(requiredAmount, expectedItemKey, requiredAmount - remaining)
         );
      }
   }

   private static long pushMatchingKey(AEKey key, long requiredAmount, KeyCounter availableInputs, PatternInputSink inputSink) {
      long available = availableInputs.get(key);
      if (available > 0L && requiredAmount > 0L) {
         long toPush = Math.min(available, requiredAmount);
         inputSink.pushInput(key, toPush);
         availableInputs.remove(key, toPush);
         return toPush;
      } else {
         return 0L;
      }
   }

   private static List<AEKey> snapshotKeys(KeyCounter counter) {
      ArrayList<AEKey> keys = new ArrayList<>();

      for (Entry<AEKey> entry : counter) {
         if (entry.getLongValue() > 0L) {
            keys.add((AEKey)entry.getKey());
         }
      }

      return keys;
   }

   private static final class OverloadInput implements IInput {
      private final IInput sourceInput;
      private final MatchMode matchMode;
      private final GenericStack[] possibleInputs;
      private final List<AEItemKey> itemKeys;

      private OverloadInput(IInput sourceInput, MatchMode matchMode) {
         this.sourceInput = sourceInput;
         this.matchMode = matchMode;
         this.possibleInputs = wipePossibleInputs(sourceInput.getPossibleInputs(), matchMode);
         this.itemKeys = collectItemKeys(sourceInput.getPossibleInputs());
      }

      private static GenericStack[] wipePossibleInputs(GenericStack[] source, MatchMode matchMode) {
         LinkedHashMap<AEKey, GenericStack> byKey = new LinkedHashMap<>(source.length);

         for (GenericStack possible : source) {
            GenericStack wiped = Ae2OverloadPatternDetails.wipeIfIdOnly(possible, matchMode);
            byKey.putIfAbsent(wiped.what(), wiped);
         }

         return byKey.values().toArray(new GenericStack[0]);
      }

      public GenericStack[] getPossibleInputs() {
         return this.possibleInputs;
      }

      public long getMultiplier() {
         return this.sourceInput.getMultiplier();
      }

      public boolean isValid(AEKey input, Level level) {
         return switch (this.matchMode) {
            case STRICT -> this.sourceInput.isValid(input, level);
            case ID_ONLY -> this.matchesItemId(input);
         };
      }

      @Nullable
      public AEKey getRemainingKey(AEKey template) {
         AEKey direct = this.sourceInput.getRemainingKey(template);
         if (direct == null && this.matchMode != MatchMode.STRICT) {
            if (template instanceof AEItemKey itemKey) {
               for (AEItemKey possible : this.itemKeys) {
                  if (possible.getItem() == itemKey.getItem()) {
                     AEKey remaining = this.sourceInput.getRemainingKey(possible);
                     if (remaining != null) {
                        return remaining;
                     }
                  }
               }
            }

            return null;
         } else {
            return direct;
         }
      }

      private boolean matchesItemId(AEKey input) {
         if (input instanceof AEItemKey itemKey) {
            for (AEItemKey possible : this.itemKeys) {
               if (possible.getItem() == itemKey.getItem()) {
                  return true;
               }
            }

            return false;
         } else {
            return false;
         }
      }

      private static List<AEItemKey> collectItemKeys(GenericStack[] possibleInputs) {
         ArrayList<AEItemKey> result = new ArrayList<>(possibleInputs.length);

         for (GenericStack possible : possibleInputs) {
            if (possible.what() instanceof AEItemKey itemKey) {
               result.add(itemKey);
            }
         }

         if (result.isEmpty()) {
            throw new IllegalArgumentException("overload patterns currently only support item inputs");
         } else {
            return List.copyOf(result);
         }
      }
   }
}
