package com.moakiee.thunderbolt.mixin.ae2.crafting;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKeyType;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;

import com.moakiee.thunderbolt.api.crafting.batch.BatchJobView;
import com.moakiee.thunderbolt.api.crafting.batch.BatchTaskHandle;

final class VanillaBatchJobView implements BatchJobView, BatchTaskHandle, Iterator<BatchTaskHandle> {
    private final ExecutingCraftingJob job;
    private final Level level;
    private final UUID craftingId;
    private Iterator<? extends Map.Entry<IPatternDetails, ?>> rawIter;
    private Map.Entry<IPatternDetails, ?> currentEntry;

    VanillaBatchJobView(ExecutingCraftingJob job, Level level) {
        this.job = job;
        this.level = level;
        this.craftingId = ((ExecutingCraftingJobAccessor) job).getLink().getCraftingID();
    }

    @Override
    public Level level() {
        return level;
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
        return craftingId;
    }

    @Override
    public void addContainerMaxItems(long count, AEKeyType type) {
        var timeTracker = ((ExecutingCraftingJobAccessor) job).getTimeTracker();
        ((ElapsedTimeTrackerAccessor) timeTracker).invokeAddMaxItems(count, type);
    }
}
