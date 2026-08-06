package com.moakiee.thunderbolt.core.crafting.batch;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKeyType;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;

import com.moakiee.thunderbolt.mixin.ae2.crafting.ElapsedTimeTrackerAccessor;
import com.moakiee.thunderbolt.mixin.ae2.crafting.ExecutingCraftingJobAccessor;
import com.moakiee.thunderbolt.mixin.ae2.crafting.TaskProgressAccessor;
import com.moakiee.thunderbolt.core.crafting.batch.BatchJobView;
import com.moakiee.thunderbolt.core.crafting.batch.BatchTaskHandle;

public final class VanillaBatchJobView implements BatchJobView, BatchTaskHandle, Iterator<BatchTaskHandle> {
    private final ExecutingCraftingJob job;
    private Iterator<? extends Map.Entry<IPatternDetails, ?>> rawIter;
    private Map.Entry<IPatternDetails, ?> currentEntry;

    public VanillaBatchJobView(ExecutingCraftingJob job) {
        this.job = job;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Iterator<BatchTaskHandle> taskIterator() {
        Map<IPatternDetails, ?> tasks = ((ExecutingCraftingJobAccessor) job).getTasks();
        rawIter = (Iterator<? extends Map.Entry<IPatternDetails, ?>>) tasks.entrySet().iterator();
        currentEntry = null;
        return this;
    }

    @Override
    public boolean hasNext() {
        return rawIter.hasNext();
    }

    @Override
    public BatchTaskHandle next() {
        currentEntry = rawIter.next();
        return this;
    }

    @Override
    public void remove() {
        rawIter.remove();
        currentEntry = null;
    }

    @Override
    public IPatternDetails details() {
        return currentEntry.getKey();
    }

    @Override
    public long getValue() {
        return ((TaskProgressAccessor) currentEntry.getValue()).getValue();
    }

    @Override
    public void setValue(long value) {
        ((TaskProgressAccessor) currentEntry.getValue()).setValue(value);
    }

    @Override
    public ListCraftingInventory waitingFor() {
        return ((ExecutingCraftingJobAccessor) job).getWaitingFor();
    }

    @Override
    public UUID craftingId() {
        return ((ExecutingCraftingJobAccessor) job).getLink().getCraftingID();
    }

    @Override
    public void addContainerMaxItems(long count, AEKeyType type) {
        var timeTracker = ((ExecutingCraftingJobAccessor) job).getTimeTracker();
        ((ElapsedTimeTrackerAccessor) timeTracker).invokeAddMaxItems(count, type);
    }
}
