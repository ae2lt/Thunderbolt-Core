package com.moakiee.thunderbolt.ae2.batch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKeyType;
import appeng.crafting.execution.ElapsedTimeTracker;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;
import com.moakiee.thunderbolt.ae2.mixin.ElapsedTimeTrackerAccessor;
import com.moakiee.thunderbolt.ae2.mixin.ExecutingCraftingJobAccessor;
import com.moakiee.thunderbolt.ae2.mixin.TaskProgressAccessor;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;

public final class VanillaBatchJobView implements BatchJobView, BatchTaskHandle, Iterator<BatchTaskHandle> {
   private final ExecutingCraftingJob job;
   private Iterator<? extends Entry<IPatternDetails, ?>> rawIter;
   private Entry<IPatternDetails, ?> currentEntry;

   public VanillaBatchJobView(ExecutingCraftingJob job) {
      this.job = job;
   }

   @Override
   public Iterator<BatchTaskHandle> taskIterator() {
      Map<IPatternDetails, ?> tasks = ((ExecutingCraftingJobAccessor)this.job).getTasks();
      this.rawIter = tasks.entrySet().iterator();
      this.currentEntry = null;
      return this;
   }

   @Override
   public boolean hasNext() {
      return this.rawIter.hasNext();
   }

   public BatchTaskHandle next() {
      this.currentEntry = (Entry<IPatternDetails, ?>)this.rawIter.next();
      return this;
   }

   @Override
   public void remove() {
      this.rawIter.remove();
      this.currentEntry = null;
   }

   @Override
   public IPatternDetails details() {
      return this.currentEntry.getKey();
   }

   @Override
   public long getValue() {
      return ((TaskProgressAccessor)this.currentEntry.getValue()).getValue();
   }

   @Override
   public void setValue(long value) {
      ((TaskProgressAccessor)this.currentEntry.getValue()).setValue(value);
   }

   @Override
   public ListCraftingInventory waitingFor() {
      return ((ExecutingCraftingJobAccessor)this.job).getWaitingFor();
   }

   @Override
   public UUID craftingId() {
      return ((ExecutingCraftingJobAccessor)this.job).getLink().getCraftingID();
   }

   @Override
   public void addContainerMaxItems(long count, AEKeyType type) {
      ElapsedTimeTracker timeTracker = ((ExecutingCraftingJobAccessor)this.job).getTimeTracker();
      ((ElapsedTimeTrackerAccessor)timeTracker).invokeAddMaxItems(count, type);
   }
}
