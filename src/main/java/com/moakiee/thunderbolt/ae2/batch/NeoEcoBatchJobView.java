package com.moakiee.thunderbolt.ae2.batch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKeyType;
import appeng.crafting.CraftingLink;
import appeng.crafting.inv.ListCraftingInventory;
import com.moakiee.thunderbolt.ae2.util.MixinReflectionSupport;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import org.jetbrains.annotations.Nullable;

public final class NeoEcoBatchJobView implements BatchJobView, BatchTaskHandle, Iterator<BatchTaskHandle> {
   @Nullable
   private static final Class<?> JOB_CLASS = MixinReflectionSupport.findClassSafe("cn.dancingsnow.neoecoae.api.me.ExecutingCraftingJob");
   @Nullable
   private static final Class<?> TASK_PROGRESS_CLASS = MixinReflectionSupport.findClassSafe("cn.dancingsnow.neoecoae.api.me.ExecutingCraftingJob$TaskProgress");
   @Nullable
   private static final Class<?> ELAPSED_TIME_TRACKER_CLASS = MixinReflectionSupport.findClassSafe("cn.dancingsnow.neoecoae.api.me.ElapsedTimeTracker");
   @Nullable
   private static final Field TASKS_FIELD = MixinReflectionSupport.findDeclaredFieldSafe(JOB_CLASS, "tasks");
   @Nullable
   private static final Field WAITING_FOR_FIELD = MixinReflectionSupport.findDeclaredFieldSafe(JOB_CLASS, "waitingFor");
   @Nullable
   private static final Field TIME_TRACKER_FIELD = MixinReflectionSupport.findDeclaredFieldSafe(JOB_CLASS, "timeTracker");
   @Nullable
   private static final Field LINK_FIELD = MixinReflectionSupport.findDeclaredFieldSafe(JOB_CLASS, "link");
   @Nullable
   private static final Field TASK_VALUE_FIELD = MixinReflectionSupport.findDeclaredFieldSafe(TASK_PROGRESS_CLASS, "value");
   @Nullable
   private static final Method ADD_MAX_ITEMS_METHOD = MixinReflectionSupport.findDeclaredMethodSafe(
      ELAPSED_TIME_TRACKER_CLASS, "addMaxItems", long.class, AEKeyType.class
   );
   private final Object job;
   private Iterator<? extends Entry<?, ?>> rawIterator = Collections.emptyIterator();
   @Nullable
   private Entry<IPatternDetails, ?> currentEntry;

   public NeoEcoBatchJobView(Object job) {
      this.job = job;
   }

   public static boolean isAvailable() {
      return JOB_CLASS != null
         && TASK_PROGRESS_CLASS != null
         && ELAPSED_TIME_TRACKER_CLASS != null
         && TASKS_FIELD != null
         && WAITING_FOR_FIELD != null
         && TIME_TRACKER_FIELD != null
         && LINK_FIELD != null
         && TASK_VALUE_FIELD != null
         && ADD_MAX_ITEMS_METHOD != null;
   }

   public static boolean acceptsJob(@Nullable Object candidate) {
      return isAvailable() && candidate != null && JOB_CLASS.isInstance(candidate);
   }

   @Override
   public Iterator<BatchTaskHandle> taskIterator() {
      if (MixinReflectionSupport.getFieldValueSafe(TASKS_FIELD, this.job) instanceof Map<?, ?> tasks) {
         this.rawIterator = tasks.entrySet().iterator();
      } else {
         this.rawIterator = Collections.emptyIterator();
      }

      this.currentEntry = null;
      return this;
   }

   @Override
   public boolean hasNext() {
      return this.rawIterator.hasNext();
   }

   public BatchTaskHandle next() {
      this.currentEntry = (Entry<IPatternDetails, ?>)this.rawIterator.next();
      return this;
   }

   @Override
   public void remove() {
      this.rawIterator.remove();
      this.currentEntry = null;
   }

   @Override
   public IPatternDetails details() {
      return this.requireCurrentEntry().getKey();
   }

   @Override
   public long getValue() {
      return MixinReflectionSupport.getLongFieldSafe(TASK_VALUE_FIELD, this.requireCurrentEntry().getValue(), 0L);
   }

   @Override
   public void setValue(long value) {
      MixinReflectionSupport.setLongFieldSafe(TASK_VALUE_FIELD, this.requireCurrentEntry().getValue(), value, "set NeoECO batch task progress");
   }

   @Override
   public ListCraftingInventory waitingFor() {
      Object waitingFor = MixinReflectionSupport.getFieldValueSafe(WAITING_FOR_FIELD, this.job);
      if (waitingFor instanceof ListCraftingInventory) {
         return (ListCraftingInventory)waitingFor;
      } else {
         throw new IllegalStateException("NeoECO crafting job has no compatible waitingFor inventory");
      }
   }

   @Nullable
   @Override
   public UUID craftingId() {
      return MixinReflectionSupport.getFieldValueSafe(LINK_FIELD, this.job) instanceof CraftingLink craftingLink ? craftingLink.getCraftingID() : null;
   }

   @Override
   public void addContainerMaxItems(long count, AEKeyType type) {
      Object tracker = MixinReflectionSupport.getFieldValueSafe(TIME_TRACKER_FIELD, this.job);
      if (tracker == null) {
         throw new IllegalStateException("NeoECO crafting job has no elapsed-time tracker");
      } else {
         MixinReflectionSupport.invokeMethodSafe(ADD_MAX_ITEMS_METHOD, tracker, "add NeoECO batch container items", count, type);
      }
   }

   private Entry<IPatternDetails, ?> requireCurrentEntry() {
      if (this.currentEntry == null) {
         throw new IllegalStateException("No current NeoECO crafting task");
      } else {
         return this.currentEntry;
      }
   }
}
