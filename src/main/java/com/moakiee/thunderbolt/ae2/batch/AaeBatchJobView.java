package com.moakiee.thunderbolt.ae2.batch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKeyType;
import appeng.crafting.inv.ListCraftingInventory;
import com.moakiee.thunderbolt.ae2.mixin.AaeElapsedTimeTrackerAccessor;
import com.moakiee.thunderbolt.ae2.mixin.AaeExecutingCraftingJobAccessor;
import com.moakiee.thunderbolt.ae2.mixin.AaeTaskProgressAccessor;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.pedroksl.advanced_ae.common.logic.ElapsedTimeTracker;
import net.pedroksl.advanced_ae.common.logic.ExecutingCraftingJob;

public final class AaeBatchJobView implements BatchJobView, BatchTaskHandle, Iterator<BatchTaskHandle> {
   private final ExecutingCraftingJob job;
   private Iterator<? extends Entry<IPatternDetails, ?>> rawIter;
   private Entry<IPatternDetails, ?> currentEntry;

   public AaeBatchJobView(ExecutingCraftingJob job) {
      this.job = job;
   }

   @Override
   public Iterator<BatchTaskHandle> taskIterator() {
      Map<IPatternDetails, ?> tasks = ((AaeExecutingCraftingJobAccessor)this.job).getTasks();
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
      return ((AaeTaskProgressAccessor)this.currentEntry.getValue()).getValue();
   }

   @Override
   public void setValue(long value) {
      ((AaeTaskProgressAccessor)this.currentEntry.getValue()).setValue(value);
   }

   @Override
   public ListCraftingInventory waitingFor() {
      return ((AaeExecutingCraftingJobAccessor)this.job).getWaitingFor();
   }

   @Override
   public UUID craftingId() {
      return ((AaeExecutingCraftingJobAccessor)this.job).getLink().getCraftingID();
   }

   @Override
   public void addContainerMaxItems(long count, AEKeyType type) {
      ElapsedTimeTracker tracker = ((AaeExecutingCraftingJobAccessor)this.job).getTimeTracker();
      ((AaeElapsedTimeTrackerAccessor)tracker).invokeAddMaxItems(count, type);
   }
}
