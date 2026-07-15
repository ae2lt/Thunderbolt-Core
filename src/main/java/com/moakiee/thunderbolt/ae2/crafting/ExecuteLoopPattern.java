package com.moakiee.thunderbolt.ae2.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.moakiee.thunderbolt.ae2.api.crafting.IProviderLookupPattern;
import com.moakiee.thunderbolt.ae2.api.crafting.IPrioritizedCraftingTask;
import com.moakiee.thunderbolt.ae2.api.crafting.ISeedPreservingCraftingTask;
import com.moakiee.thunderbolt.ae2.api.crafting.CraftingPatternDelegates;
import com.moakiee.thunderbolt.ae2.overload.pattern.OverloadedProviderOnlyPatternDetails;
import com.moakiee.thunderbolt.ae2.timewheel.TimeWheelTaskPersistenceDefinition;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Execution-only wrapper for one closed-loop member task. Provider lookup and pushing still use
 * the delegate pattern; the TimeWheel CPU uses the two counters to update the cycle's virtual seed
 * ledger while extracting inputs and accepting returns. Outputs not listed in {@code outputSeed}
 * remain ordinary public outputs.
 */
public final class ExecuteLoopPattern implements IPatternDetails, IProviderLookupPattern,
        IPrioritizedCraftingTask, ISeedPreservingCraftingTask,
        TimeWheelTaskPersistenceDefinition {
    private final IPatternDetails delegate;
    private final KeyCounter inputSeed;
    private final KeyCounter outputSeed;

    public ExecuteLoopPattern(
            IPatternDetails delegate,
            KeyCounter inputSeed,
            KeyCounter outputSeed) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (!(delegate instanceof ISeedPreservingCraftingTask)) {
            throw new IllegalArgumentException("loop execution delegate must identify its seed group");
        }
        if (!(delegate instanceof TimeWheelTaskPersistenceDefinition)) {
            throw new IllegalArgumentException("loop execution delegate must be persistable");
        }
        this.inputSeed = copy(inputSeed);
        this.outputSeed = copy(outputSeed);
    }

    public IPatternDetails delegate() { return delegate; }
    public KeyCounter inputSeed() { return copy(inputSeed); }
    public KeyCounter outputSeed() { return copy(outputSeed); }

    /** Whether this concrete key belongs to one of this task's reusable-seed input slots. */
    public boolean isInputSeedKey(AEKey key) {
        if (key == null) return false;
        if (inputSeed.get(key) > 0) return true;
        var inputs = delegate.getInputs();
        for (int slot = 0; slot < inputs.length; slot++) {
            if (!isSeedSlot(slot)) continue;
            boolean fuzzy = isFuzzyInput(slot);
            // Once analysis identifies a slot as the reusable-state input, any concrete template
            // that AE2 may legally resolve for that slot becomes the physical debit key. This also
            // covers ordinary substitution inputs with multiple exact choices, not just ID_ONLY.
            for (var possible : inputs[slot].getPossibleInputs()) {
                if (possible.what() != null && matches(possible.what(), key, fuzzy)) return true;
            }
        }
        return false;
    }

    /** Fuzzy seed slots must report the exact key physically extracted by the CPU. */
    public boolean requiresActualSeedKeyTracking() {
        var inputs = delegate.getInputs();
        for (int slot = 0; slot < inputs.length; slot++) {
            if (isSeedSlot(slot)
                    && (isFuzzyInput(slot) || inputs[slot].getPossibleInputs().length > 1)) {
                return true;
            }
        }
        return false;
    }

    /** Extracts only the reusable-seed portion from AE2's resolved per-slot input holders. */
    public KeyCounter actualInputSeed(KeyCounter[] inputHolders) {
        var result = new KeyCounter();
        if (inputHolders == null) return result;
        int slots = Math.min(delegate.getInputs().length, inputHolders.length);
        for (int slot = 0; slot < slots; slot++) {
            if (!isSeedSlot(slot) || inputHolders[slot] == null) continue;
            for (var actual : inputHolders[slot]) {
                if (actual.getLongValue() > 0) {
                    result.add(actual.getKey(), actual.getLongValue());
                }
            }
        }
        return result;
    }

    @Override public IPatternDetails providerLookupPattern() { return delegate; }
    @Override public AEItemKey timeWheelPersistenceDefinition() {
        return ((TimeWheelTaskPersistenceDefinition) delegate).timeWheelPersistenceDefinition();
    }
    @Override public int dispatchPriority() {
        return delegate instanceof IPrioritizedCraftingTask prioritized
                ? prioritized.dispatchPriority() : 0;
    }
    @Override public int dispatchOrder() {
        return delegate instanceof IPrioritizedCraftingTask prioritized
                ? prioritized.dispatchOrder() : 0;
    }
    @Override public UUID reusableSeedGroupId() {
        return ((ISeedPreservingCraftingTask) delegate).reusableSeedGroupId();
    }
    @Override public Set<AEKey> reusableSeedCycleKeys() {
        return ((ISeedPreservingCraftingTask) delegate).reusableSeedCycleKeys();
    }
    @Override public boolean hasSingleSeedInputPerMember() {
        return ((ISeedPreservingCraftingTask) delegate).hasSingleSeedInputPerMember();
    }
    @Override public AEItemKey getDefinition() { return delegate.getDefinition(); }
    @Override public IInput[] getInputs() { return delegate.getInputs(); }
    @Override public List<GenericStack> getOutputs() { return delegate.getOutputs(); }
    @Override public boolean supportsPushInputsToExternalInventory() {
        return delegate.supportsPushInputsToExternalInventory();
    }
    @Override public void pushInputsToExternalInventory(
            KeyCounter[] inputHolder, PatternInputSink inputSink) {
        delegate.pushInputsToExternalInventory(inputHolder, inputSink);
    }

    private static KeyCounter copy(KeyCounter source) {
        var result = new KeyCounter();
        if (source != null) result.addAll(source);
        return result;
    }

    private boolean isSeedSlot(int slot) {
        var inputs = delegate.getInputs();
        if (slot < 0 || slot >= inputs.length) return false;
        boolean fuzzy = isFuzzyInput(slot);
        for (var possible : inputs[slot].getPossibleInputs()) {
            if (possible.what() == null) continue;
            for (var expected : inputSeed) {
                if (matches(expected.getKey(), possible.what(), fuzzy)) return true;
            }
        }
        return false;
    }

    private boolean isFuzzyInput(int slot) {
        var providerPattern = CraftingPatternDelegates.forProviderLookup(delegate);
        return providerPattern instanceof OverloadedProviderOnlyPatternDetails overload
                && overload.isFuzzyInput(slot);
    }

    private static boolean matches(AEKey expected, AEKey actual, boolean fuzzy) {
        return expected.equals(actual)
                || (fuzzy && expected.dropSecondary().equals(actual.dropSecondary()));
    }
}
