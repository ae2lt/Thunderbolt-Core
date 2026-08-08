package com.moakiee.thunderbolt.ae2.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetails.IInput;
import appeng.api.crafting.IPatternDetails.PatternInputSink;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.moakiee.thunderbolt.ae2.api.crafting.CraftingPatternDelegates;
import com.moakiee.thunderbolt.ae2.api.crafting.IPlannedSeedSlotPattern;
import com.moakiee.thunderbolt.ae2.api.crafting.IPrioritizedCraftingTask;
import com.moakiee.thunderbolt.ae2.api.crafting.IProviderLookupPattern;
import com.moakiee.thunderbolt.ae2.api.crafting.ISeedPreservingCraftingTask;
import com.moakiee.thunderbolt.ae2.overload.pattern.OverloadedProviderOnlyPatternDetails;
import com.moakiee.thunderbolt.ae2.timewheel.TimeWheelTaskPersistenceDefinition;
import it.unimi.dsi.fastutil.objects.Object2LongMap.Entry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.level.Level;

public final class ExecuteLoopPattern
   implements IPatternDetails,
   IProviderLookupPattern,
   IPrioritizedCraftingTask,
   ISeedPreservingCraftingTask,
   TimeWheelTaskPersistenceDefinition {
   public static final UUID SHARED_SEED_ACCOUNT_ID = UUID.fromString("ae2ae2ae-51ed-4acc-8000-000000000001");
   private final IPatternDetails delegate;
   private final UUID seedConsumerId;
   private final KeyCounter initialSeed;
   private final KeyCounter inputSeed;
   private final Map<UUID, KeyCounter> outputSeedCredits;
   private final Map<UUID, KeyCounter> sharedOutputSeedCredits;
   private final IInput[] executionInputs;
   private final Map<Integer, AEKey> plannedSlots;
   private final boolean[] fuzzyInputs;
   private final boolean[] seedSlots;
   private final Map<AEKey, ExecuteLoopPattern.SeedVariantRule> plannedSeedRules;
   private final int dispatchPriority;
   private final int dispatchOrder;
   private final int hashCode;

   public ExecuteLoopPattern(
      IPatternDetails delegate, UUID seedConsumerId, KeyCounter initialSeed, KeyCounter inputSeed, Map<UUID, KeyCounter> outputSeedCredits
   ) {
      this(delegate, seedConsumerId, initialSeed, inputSeed, outputSeedCredits, Map.of());
   }

   public ExecuteLoopPattern(
      IPatternDetails delegate,
      UUID seedConsumerId,
      KeyCounter initialSeed,
      KeyCounter inputSeed,
      Map<UUID, KeyCounter> outputSeedCredits,
      Map<UUID, KeyCounter> sharedOutputSeedCredits
   ) {
      this.delegate = Objects.requireNonNull(delegate, "delegate");
      if (!(delegate instanceof ISeedPreservingCraftingTask)) {
         throw new IllegalArgumentException("loop execution delegate must identify its seed group");
      } else if (!(delegate instanceof TimeWheelTaskPersistenceDefinition)) {
         throw new IllegalArgumentException("loop execution delegate must be persistable");
      } else {
         this.seedConsumerId = Objects.requireNonNull(seedConsumerId, "seedConsumerId");
         this.initialSeed = copy(initialSeed);
         this.inputSeed = copy(inputSeed);
         this.outputSeedCredits = copyCredits(outputSeedCredits);
         this.sharedOutputSeedCredits = copyCredits(sharedOutputSeedCredits);
         this.plannedSlots = snapshotPlannedSeedSlots(delegate);
         IInput[] sourceInputs = delegate.getInputs();
         this.fuzzyInputs = computeFuzzyInputs(delegate, sourceInputs.length);
         this.executionInputs = this.constrainRepeatedPlannedSeedSlots(sourceInputs);
         this.seedSlots = this.computeSeedSlots();
         this.plannedSeedRules = this.computePlannedSeedRules();
         this.dispatchPriority = delegate instanceof IPrioritizedCraftingTask prioritized ? prioritized.dispatchPriority() : 0;
         this.dispatchOrder = delegate instanceof IPrioritizedCraftingTask prioritizedx ? prioritizedx.dispatchOrder() : 0;
         this.hashCode = this.computeHashCode();
      }
   }

   private static Map<Integer, AEKey> snapshotPlannedSeedSlots(IPatternDetails delegate) {
      if (delegate instanceof IPlannedSeedSlotPattern mapped) {
         Map<Integer, AEKey> source = mapped.plannedSeedInputSlots();
         return source != null && !source.isEmpty() ? Collections.unmodifiableMap(new LinkedHashMap<>(source)) : Map.of();
      } else {
         return Map.of();
      }
   }

   private static boolean[] computeFuzzyInputs(IPatternDetails delegate, int slotCount) {
      boolean[] result = new boolean[slotCount];
      if (CraftingPatternDelegates.forProviderLookup(delegate) instanceof OverloadedProviderOnlyPatternDetails overload) {
         for (int slot = 0; slot < slotCount; slot++) {
            result[slot] = overload.isFuzzyInput(slot);
         }
      }

      return result;
   }

   private boolean[] computeSeedSlots() {
      boolean[] result = new boolean[this.executionInputs.length];

      for (int slot = 0; slot < result.length; slot++) {
         result[slot] = this.computeIsSeedSlot(slot);
      }

      return result;
   }

   private Map<AEKey, ExecuteLoopPattern.SeedVariantRule> computePlannedSeedRules() {
      LinkedHashMap<AEKey, ExecuteLoopPattern.SeedVariantRule> result = new LinkedHashMap<>();

      for (Entry<AEKey> planned : this.inputSeed) {
         result.put((AEKey)planned.getKey(), this.computeSeedVariantRule((AEKey)planned.getKey()));
      }

      return Collections.unmodifiableMap(result);
   }

   public IPatternDetails delegate() {
      return this.delegate;
   }

   public UUID seedConsumerId() {
      return this.seedConsumerId;
   }

   public KeyCounter initialSeed() {
      return copy(this.initialSeed);
   }

   public KeyCounter inputSeed() {
      return copy(this.inputSeed);
   }

   public Map<UUID, KeyCounter> outputSeedCredits() {
      return copyCredits(this.outputSeedCredits);
   }

   public Map<UUID, KeyCounter> sharedOutputSeedCredits() {
      return copyCredits(this.sharedOutputSeedCredits);
   }

   public Map<UUID, KeyCounter> runtimeOutputSeedCredits() {
      LinkedHashMap<UUID, KeyCounter> result = new LinkedHashMap<>();

      for (java.util.Map.Entry<UUID, KeyCounter> entry : this.outputSeedCredits.entrySet()) {
         result.put(entry.getKey(), copy(entry.getValue()));
      }

      KeyCounter shared = new KeyCounter();

      for (KeyCounter credit : this.sharedOutputSeedCredits.values()) {
         shared.addAll(credit);
      }

      if (!shared.isEmpty()) {
         result.put(SHARED_SEED_ACCOUNT_ID, shared);
      }

      return Collections.unmodifiableMap(result);
   }

   public KeyCounter outputSeed() {
      KeyCounter result = new KeyCounter();

      for (KeyCounter credit : this.outputSeedCredits.values()) {
         result.addAll(credit);
      }

      for (KeyCounter credit : this.sharedOutputSeedCredits.values()) {
         result.addAll(credit);
      }

      return result;
   }

   public Map<UUID, Long> outputSeedConsumers(AEKey expectedKey) {
      if (expectedKey == null) {
         return Map.of();
      } else {
         LinkedHashMap<UUID, Long> result = new LinkedHashMap<>();

         for (java.util.Map.Entry<UUID, KeyCounter> credit : this.outputSeedCredits.entrySet()) {
            long amount = credit.getValue().get(expectedKey);
            if (amount > 0L) {
               result.put(credit.getKey(), Long.valueOf(amount));
            }
         }

         long shared = 0L;

         for (KeyCounter creditx : this.sharedOutputSeedCredits.values()) {
            shared = saturatingAdd(shared, creditx.get(expectedKey));
         }

         if (shared > 0L) {
            result.put(SHARED_SEED_ACCOUNT_ID, Long.valueOf(shared));
         }

         return Collections.unmodifiableMap(result);
      }
   }

   public boolean isInputSeedKey(AEKey key) {
      if (key == null) {
         return false;
      } else if (this.inputSeed.get(key) > 0L) {
         return true;
      } else {
         for (Entry<AEKey> planned : this.inputSeed) {
            if (this.seedVariantRule((AEKey)planned.getKey()).accepts(key)) {
               return true;
            }
         }

         return false;
      }
   }

   public boolean acceptsInputSeedVariant(AEKey planned, AEKey actual) {
      return planned != null && actual != null ? this.seedVariantRule(planned).accepts(actual) : false;
   }

   public ExecuteLoopPattern.SeedVariantRule seedVariantRule(AEKey planned) {
      if (planned == null) {
         return new ExecuteLoopPattern.SeedVariantRule(Set.of(), Set.of());
      } else {
         ExecuteLoopPattern.SeedVariantRule cached = this.plannedSeedRules.get(planned);
         return cached != null ? cached : this.computeSeedVariantRule(planned);
      }
   }

   private ExecuteLoopPattern.SeedVariantRule computeSeedVariantRule(AEKey planned) {
      ExecuteLoopPattern.SeedVariantRule combined = null;
      IInput[] inputs = this.executionInputs;
      Map<Integer, AEKey> plannedSlots = this.plannedSeedInputSlots();

      for (int slot = 0; slot < inputs.length; slot++) {
         if ((plannedSlots.isEmpty() || planned.equals(plannedSlots.get(slot))) && this.isSeedSlot(slot)) {
            ExecuteLoopPattern.SeedVariantRule slotRule = this.seedVariantRuleForSlot(slot, planned, inputs[slot]);
            if (slotRule != null) {
               combined = combined == null ? slotRule : combined.intersect(slotRule);
            }
         }
      }

      return combined != null ? combined : new ExecuteLoopPattern.SeedVariantRule(Set.of(planned), Set.of());
   }

   public long inputSeedAmountFor(AEKey key) {
      if (key == null) {
         return 0L;
      } else {
         long exact = this.inputSeed.get(key);
         if (exact > 0L) {
            return exact;
         } else {
            long result = 0L;

            for (Entry<AEKey> planned : this.inputSeed) {
               if (this.seedVariantRule((AEKey)planned.getKey()).accepts(key)) {
                  result = saturatingAdd(result, planned.getLongValue());
               }
            }

            return result;
         }
      }
   }

   public boolean requiresActualSeedKeyTracking() {
      IInput[] inputs = this.executionInputs;

      for (int slot = 0; slot < inputs.length; slot++) {
         if (this.isSeedSlot(slot) && (this.isFuzzyInput(slot) || inputs[slot].getPossibleInputs().length > 1)) {
            return true;
         }
      }

      return false;
   }

   public KeyCounter actualInputSeed(KeyCounter[] inputHolders) {
      KeyCounter result = new KeyCounter();

      for (ExecuteLoopPattern.ActualSeedUse use : this.resolveActualInputSeedUses(inputHolders).uses()) {
         result.add(use.actual(), use.amount());
      }

      return result;
   }

   public List<ExecuteLoopPattern.ActualSeedUse> actualInputSeedUses(KeyCounter[] inputHolders) {
      return this.resolveActualInputSeedUses(inputHolders).uses();
   }

   public ExecuteLoopPattern.ActualSeedResolution resolveActualInputSeedUses(KeyCounter[] inputHolders) {
      if (inputHolders == null) {
         return new ExecuteLoopPattern.ActualSeedResolution(List.of(), false);
      } else {
         ArrayList<ExecuteLoopPattern.ActualSeedUse> result = new ArrayList<>();
         LinkedHashMap<AEKey, Long> remainingPlanned = new LinkedHashMap<>();

         for (Entry<AEKey> planned : this.inputSeed) {
            if (planned.getLongValue() > 0L) {
               remainingPlanned.put((AEKey)planned.getKey(), Long.valueOf(planned.getLongValue()));
            }
         }

         int slots = Math.min(this.executionInputs.length, inputHolders.length);
         boolean complete = inputHolders.length >= this.executionInputs.length;

         for (int slot = 0; slot < slots; slot++) {
            if (this.isSeedSlot(slot) && inputHolders[slot] != null) {
               for (Entry<AEKey> actual : inputHolders[slot]) {
                  long amount = actual.getLongValue();
                  if (amount > 0L) {
                     while (amount > 0L) {
                        AEKey plannedx = this.plannedSeedForSlot(slot, (AEKey)actual.getKey(), remainingPlanned);
                        if (plannedx == null) {
                           complete = false;
                           break;
                        }

                        long used = Math.min(amount, remainingPlanned.getOrDefault(plannedx, 0L));
                        if (used <= 0L) {
                           complete = false;
                           break;
                        }

                        IInput input = this.executionInputs[slot];
                        result.add(
                           new ExecuteLoopPattern.ActualSeedUse(
                              plannedx,
                              (AEKey)actual.getKey(),
                              input.getRemainingKey(plannedx),
                              input.getRemainingKey((AEKey)actual.getKey()),
                              used,
                              this.remainderOperations(slot, input, (AEKey)actual.getKey(), used)
                           )
                        );
                        amount -= used;
                        long left = remainingPlanned.get(plannedx) - used;
                        if (left > 0L) {
                           remainingPlanned.put(plannedx, Long.valueOf(left));
                        } else {
                           remainingPlanned.remove(plannedx);
                        }
                     }
                  }
               }
            }
         }

         if (!remainingPlanned.isEmpty()) {
            complete = false;
         }

         return new ExecuteLoopPattern.ActualSeedResolution(result, complete);
      }
   }

   @Override
   public IPatternDetails providerLookupPattern() {
      return this.delegate;
   }

   @Override
   public AEItemKey timeWheelPersistenceDefinition() {
      return ((TimeWheelTaskPersistenceDefinition)this.delegate).timeWheelPersistenceDefinition();
   }

   @Override
   public int dispatchPriority() {
      return this.dispatchPriority;
   }

   @Override
   public int dispatchOrder() {
      return this.dispatchOrder;
   }

   @Override
   public UUID reusableSeedGroupId() {
      return ((ISeedPreservingCraftingTask)this.delegate).reusableSeedGroupId();
   }

   @Override
   public Set<AEKey> reusableSeedCycleKeys() {
      return ((ISeedPreservingCraftingTask)this.delegate).reusableSeedCycleKeys();
   }

   @Override
   public boolean hasSingleSeedInputPerMember() {
      return ((ISeedPreservingCraftingTask)this.delegate).hasSingleSeedInputPerMember();
   }

   public AEItemKey getDefinition() {
      return this.delegate.getDefinition();
   }

   public IInput[] getInputs() {
      return (IInput[])this.executionInputs.clone();
   }

   public GenericStack[] getOutputs() {
      return this.delegate.getOutputs();
   }

   public boolean supportsPushInputsToExternalInventory() {
      return this.delegate.supportsPushInputsToExternalInventory();
   }

   public void pushInputsToExternalInventory(KeyCounter[] inputHolder, PatternInputSink inputSink) {
      this.delegate.pushInputsToExternalInventory(inputHolder, inputSink);
   }

   @Override
   public boolean equals(Object obj) {
      if (this == obj) {
         return true;
      } else {
         if (obj instanceof ExecuteLoopPattern other && this.hashCode == other.hashCode) {
            return this.delegate.equals(other.delegate)
               && this.seedConsumerId.equals(other.seedConsumerId)
               && counterEquals(this.initialSeed, other.initialSeed)
               && counterEquals(this.inputSeed, other.inputSeed)
               && creditMapsEqual(this.outputSeedCredits, other.outputSeedCredits)
               && creditMapsEqual(this.sharedOutputSeedCredits, other.sharedOutputSeedCredits);
         }

         return false;
      }
   }

   @Override
   public int hashCode() {
      return this.hashCode;
   }

   private int computeHashCode() {
      int result = this.delegate.hashCode();
      result = 31 * result + this.seedConsumerId.hashCode();
      result = 31 * result + counterHashCode(this.initialSeed);
      result = 31 * result + counterHashCode(this.inputSeed);
      result = 31 * result + creditMapHashCode(this.outputSeedCredits);
      return 31 * result + creditMapHashCode(this.sharedOutputSeedCredits);
   }

   private static boolean counterEquals(KeyCounter left, KeyCounter right) {
      if (left == right) {
         return true;
      } else {
         int leftEntries = 0;

         for (Entry<AEKey> entry : left) {
            long amount = entry.getLongValue();
            if (amount != 0L) {
               leftEntries++;
               if (right.get((AEKey)entry.getKey()) != amount) {
                  return false;
               }
            }
         }

         int rightEntries = 0;

         for (Entry<AEKey> entryx : right) {
            if (entryx.getLongValue() != 0L) {
               rightEntries++;
            }
         }

         return leftEntries == rightEntries;
      }
   }

   private static int counterHashCode(KeyCounter counter) {
      int result = 0;

      for (Entry<AEKey> entry : counter) {
         long amount = entry.getLongValue();
         if (amount != 0L) {
            result += ((AEKey)entry.getKey()).hashCode() ^ Long.hashCode(amount);
         }
      }

      return result;
   }

   private static boolean creditMapsEqual(Map<UUID, KeyCounter> left, Map<UUID, KeyCounter> right) {
      if (left == right) {
         return true;
      } else if (left.size() != right.size()) {
         return false;
      } else {
         for (java.util.Map.Entry<UUID, KeyCounter> entry : left.entrySet()) {
            KeyCounter other = right.get(entry.getKey());
            if (other == null || !counterEquals(entry.getValue(), other)) {
               return false;
            }
         }

         return true;
      }
   }

   private static int creditMapHashCode(Map<UUID, KeyCounter> credits) {
      int result = 0;

      for (java.util.Map.Entry<UUID, KeyCounter> entry : credits.entrySet()) {
         result += entry.getKey().hashCode() ^ counterHashCode(entry.getValue());
      }

      return result;
   }

   private static KeyCounter copy(KeyCounter source) {
      KeyCounter result = new KeyCounter();
      if (source != null) {
         result.addAll(source);
      }

      return result;
   }

   private static Map<UUID, KeyCounter> copyCredits(Map<UUID, KeyCounter> source) {
      LinkedHashMap<UUID, KeyCounter> result = new LinkedHashMap<>();
      if (source != null) {
         for (java.util.Map.Entry<UUID, KeyCounter> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
               result.put(entry.getKey(), copy(entry.getValue()));
            }
         }
      }

      return Collections.unmodifiableMap(result);
   }

   private boolean isSeedSlot(int slot) {
      return slot >= 0 && slot < this.seedSlots.length && this.seedSlots[slot];
   }

   private boolean computeIsSeedSlot(int slot) {
      IInput[] inputs = this.executionInputs;
      if (slot >= 0 && slot < inputs.length) {
         Map<Integer, AEKey> plannedSlots = this.plannedSeedInputSlots();
         if (!plannedSlots.isEmpty()) {
            AEKey planned = plannedSlots.get(slot);
            return planned != null && this.inputSeed.get(planned) > 0L;
         } else {
            boolean fuzzy = this.isFuzzyInput(slot);

            for (GenericStack possible : inputs[slot].getPossibleInputs()) {
               if (possible.what() != null) {
                  for (Entry<AEKey> expected : this.inputSeed) {
                     if (matches((AEKey)expected.getKey(), possible.what(), fuzzy)) {
                        return true;
                     }
                  }
               }
            }

            return false;
         }
      } else {
         return false;
      }
   }

   private boolean isFuzzyInput(int slot) {
      return slot >= 0 && slot < this.fuzzyInputs.length && this.fuzzyInputs[slot];
   }

   private AEKey plannedSeedForSlot(int slot, AEKey actual, Map<AEKey, Long> remainingPlanned) {
      Map<Integer, AEKey> plannedSlots = this.plannedSeedInputSlots();
      if (!plannedSlots.isEmpty()) {
         AEKey planned = plannedSlots.get(slot);
         if (planned != null && remainingPlanned.getOrDefault(planned, 0L) > 0L) {
            ExecuteLoopPattern.SeedVariantRule rule = this.seedVariantRuleForSlot(slot, planned, this.executionInputs[slot]);
            return rule != null && rule.accepts(actual) ? planned : null;
         } else {
            return null;
         }
      } else {
         AEKey fuzzyMatch = null;
         AEKey alternativeMatch = null;
         boolean fuzzy = this.isFuzzyInput(slot);
         GenericStack[] possible = this.executionInputs[slot].getPossibleInputs();
         boolean acceptsActual = false;

         for (GenericStack option : possible) {
            if (option.what() != null && matches(option.what(), actual, fuzzy)) {
               acceptsActual = true;
               break;
            }
         }

         for (java.util.Map.Entry<AEKey, Long> planned : remainingPlanned.entrySet()) {
            if (planned.getValue() > 0L) {
               boolean belongsToSlot = false;

               for (GenericStack optionx : possible) {
                  if (optionx.what() != null && matches(planned.getKey(), optionx.what(), fuzzy)) {
                     belongsToSlot = true;
                     break;
                  }
               }

               if (belongsToSlot) {
                  if (planned.getKey().equals(actual)) {
                     return planned.getKey();
                  }

                  if (matches(planned.getKey(), actual, fuzzy)) {
                     fuzzyMatch = planned.getKey();
                  }

                  if (acceptsActual && alternativeMatch == null) {
                     alternativeMatch = planned.getKey();
                  }
               }
            }
         }

         return fuzzyMatch != null ? fuzzyMatch : alternativeMatch;
      }
   }

   private Map<Integer, AEKey> plannedSeedInputSlots() {
      return this.plannedSlots;
   }

   private IInput[] constrainRepeatedPlannedSeedSlots(IInput[] source) {
      IInput[] result = (IInput[])source.clone();
      Map<Integer, AEKey> plannedSlots = this.plannedSeedInputSlots();
      if (plannedSlots.isEmpty()) {
         return result;
      } else {
         LinkedHashMap<AEKey, List<Integer>> slotsByPlanned = new LinkedHashMap<>();

         for (java.util.Map.Entry<Integer, AEKey> entry : plannedSlots.entrySet()) {
            if (entry.getKey() < 0 || entry.getKey() >= source.length || entry.getValue() == null) {
               throw new IllegalArgumentException("planned reusable-seed slot is invalid");
            }

            slotsByPlanned.computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>()).add(entry.getKey());
         }

         for (java.util.Map.Entry<AEKey, List<Integer>> group : slotsByPlanned.entrySet()) {
            if (group.getValue().size() >= 2) {
               ExecuteLoopPattern.SeedVariantRule common = null;

               for (int slot : group.getValue()) {
                  ExecuteLoopPattern.SeedVariantRule rule = this.seedVariantRuleForSlot(slot, group.getKey(), source[slot]);
                  if (rule == null) {
                     throw new IllegalArgumentException("planned reusable seed is not accepted by its mapped slot");
                  }

                  common = common == null ? rule : common.intersect(rule);
               }

               if (common == null || !common.accepts(group.getKey())) {
                  throw new IllegalArgumentException("repeated reusable-seed slots have no common safe state");
               }

               for (int slot : group.getValue()) {
                  result[slot] = new ExecuteLoopPattern.VariantConstrainedInput(source[slot], common);
               }
            }
         }

         return result;
      }
   }

   private ExecuteLoopPattern.SeedVariantRule seedVariantRuleForSlot(int slot, AEKey planned, IInput input) {
      boolean fuzzy = this.isFuzzyInput(slot);
      boolean containsPlanned = false;
      LinkedHashSet<AEKey> exact = new LinkedHashSet<>();
      LinkedHashSet<AEKey> fuzzyIdentities = new LinkedHashSet<>();
      exact.add(planned);

      for (GenericStack possible : input.getPossibleInputs()) {
         if (possible.what() != null) {
            containsPlanned |= matches(planned, possible.what(), fuzzy);
            exact.add(possible.what());
            if (fuzzy) {
               fuzzyIdentities.add(possible.what().dropSecondary());
            }
         }
      }

      if (!containsPlanned) {
         return null;
      } else {
         ExecuteLoopPattern.SeedVariantRule result = new ExecuteLoopPattern.SeedVariantRule(exact, fuzzyIdentities);
         if (input instanceof ExecuteLoopPattern.VariantConstrainedInput constrained) {
            result = result.intersect(constrained.allowed);
         }

         return result;
      }
   }

   private long remainderOperations(int slot, IInput input, AEKey actual, long usedAmount) {
      if (input.getRemainingKey(actual) == null) {
         return 0L;
      } else {
         long templateAmount = -1L;

         for (GenericStack possible : input.getPossibleInputs()) {
            if (possible.what() != null && matches(possible.what(), actual, this.isFuzzyInput(slot))) {
               if (possible.amount() <= 0L) {
                  return 0L;
               }

               if (templateAmount >= 0L && templateAmount != possible.amount()) {
                  return 0L;
               }

               templateAmount = possible.amount();
            }
         }

         return templateAmount > 0L && usedAmount % templateAmount == 0L ? usedAmount / templateAmount : 0L;
      }
   }

   private static boolean matches(AEKey expected, AEKey actual, boolean fuzzy) {
      return expected.equals(actual) || fuzzy && expected.dropSecondary().equals(actual.dropSecondary());
   }

   private static long saturatingAdd(long left, long right) {
      return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
   }

   public static record ActualSeedResolution(List<ExecuteLoopPattern.ActualSeedUse> uses, boolean complete) {
      public ActualSeedResolution(List<ExecuteLoopPattern.ActualSeedUse> uses, boolean complete) {
         uses = List.copyOf(Objects.requireNonNull(uses, "uses"));
         this.uses = uses;
         this.complete = complete;
      }
   }

   public static record ActualSeedUse(AEKey planned, AEKey actual, AEKey plannedRemainder, AEKey actualRemainder, long amount, long remainderAmount) {
      public ActualSeedUse(AEKey planned, AEKey actual, AEKey plannedRemainder, AEKey actualRemainder, long amount, long remainderAmount) {
         Objects.requireNonNull(planned, "planned");
         Objects.requireNonNull(actual, "actual");
         if (amount <= 0L) {
            throw new IllegalArgumentException("amount must be positive");
         } else if (remainderAmount < 0L) {
            throw new IllegalArgumentException("remainderAmount must not be negative");
         } else {
            this.planned = planned;
            this.actual = actual;
            this.plannedRemainder = plannedRemainder;
            this.actualRemainder = actualRemainder;
            this.amount = amount;
            this.remainderAmount = remainderAmount;
         }
      }
   }

   public static record SeedVariantRule(Set<AEKey> exactVariants, Set<AEKey> fuzzyIdentities) {
      public SeedVariantRule(Set<AEKey> exactVariants, Set<AEKey> fuzzyIdentities) {
         exactVariants = Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(exactVariants, "exactVariants")));
         fuzzyIdentities = Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(fuzzyIdentities, "fuzzyIdentities")));
         this.exactVariants = exactVariants;
         this.fuzzyIdentities = fuzzyIdentities;
      }

      public boolean accepts(AEKey actual) {
         return actual == null ? false : this.exactVariants.contains(actual) || this.fuzzyIdentities.contains(actual.dropSecondary());
      }

      public ExecuteLoopPattern.SeedVariantRule merge(ExecuteLoopPattern.SeedVariantRule other) {
         Objects.requireNonNull(other, "other");
         LinkedHashSet<AEKey> exact = new LinkedHashSet<>(this.exactVariants);
         exact.addAll(other.exactVariants);
         LinkedHashSet<AEKey> fuzzy = new LinkedHashSet<>(this.fuzzyIdentities);
         fuzzy.addAll(other.fuzzyIdentities);
         return new ExecuteLoopPattern.SeedVariantRule(exact, fuzzy);
      }

      public ExecuteLoopPattern.SeedVariantRule intersect(ExecuteLoopPattern.SeedVariantRule other) {
         Objects.requireNonNull(other, "other");
         LinkedHashSet<AEKey> exact = new LinkedHashSet<>();
         LinkedHashSet<AEKey> candidates = new LinkedHashSet<>(this.exactVariants);
         candidates.addAll(other.exactVariants);

         for (AEKey candidate : candidates) {
            if (this.accepts(candidate) && other.accepts(candidate)) {
               exact.add(candidate);
            }
         }

         LinkedHashSet<AEKey> fuzzy = new LinkedHashSet<>(this.fuzzyIdentities);
         fuzzy.retainAll(other.fuzzyIdentities);
         return new ExecuteLoopPattern.SeedVariantRule(exact, fuzzy);
      }
   }

   private static final class VariantConstrainedInput implements IInput {
      private final IInput source;
      private final ExecuteLoopPattern.SeedVariantRule allowed;
      private final GenericStack[] possible;

      private VariantConstrainedInput(IInput source, ExecuteLoopPattern.SeedVariantRule allowed) {
         this.source = Objects.requireNonNull(source, "source");
         this.allowed = Objects.requireNonNull(allowed, "allowed");
         ArrayList<GenericStack> filtered = new ArrayList<>();

         for (GenericStack candidate : source.getPossibleInputs()) {
            if (candidate.what() != null && allowed.accepts(candidate.what())) {
               filtered.add(candidate);
            }
         }

         if (filtered.isEmpty()) {
            throw new IllegalArgumentException("reusable-seed slot has no safely executable variant");
         } else {
            this.possible = filtered.toArray(GenericStack[]::new);
         }
      }

      public GenericStack[] getPossibleInputs() {
         return (GenericStack[])this.possible.clone();
      }

      public long getMultiplier() {
         return this.source.getMultiplier();
      }

      public boolean isValid(AEKey input, Level level) {
         return this.allowed.accepts(input) && this.source.isValid(input, level);
      }

      public AEKey getRemainingKey(AEKey template) {
         return this.allowed.accepts(template) ? this.source.getRemainingKey(template) : null;
      }
   }
}
