package com.moakiee.thunderbolt.ae2.batch;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKeyType;
import appeng.crafting.inv.ListCraftingInventory;

import com.moakiee.thunderbolt.ae2.util.MixinReflectionSupport;

/**
 * Reflection-backed batch view for NeoECO's optional crafting job classes.
 *
 * <p>Thunderbolt deliberately does not link NeoECO at compile time. The target classes are
 * resolved only when NeoECO is present, while the batching code continues to use the common
 * {@link BatchJobView} contract.
 */
public final class NeoEcoBatchJobView implements BatchJobView, BatchTaskHandle, Iterator<BatchTaskHandle> {
    private static final @Nullable Class<?> JOB_CLASS = MixinReflectionSupport.findClassSafe(
            "cn.dancingsnow.neoecoae.api.me.ExecutingCraftingJob");
    private static final @Nullable Class<?> TASK_PROGRESS_CLASS = MixinReflectionSupport.findClassSafe(
            "cn.dancingsnow.neoecoae.api.me.ExecutingCraftingJob$TaskProgress");
    private static final @Nullable Class<?> ELAPSED_TIME_TRACKER_CLASS = MixinReflectionSupport.findClassSafe(
            "cn.dancingsnow.neoecoae.api.me.ElapsedTimeTracker");

    private static final @Nullable Field TASKS_FIELD =
            MixinReflectionSupport.findDeclaredFieldSafe(JOB_CLASS, "tasks");
    private static final @Nullable Field WAITING_FOR_FIELD =
            MixinReflectionSupport.findDeclaredFieldSafe(JOB_CLASS, "waitingFor");
    private static final @Nullable Field TIME_TRACKER_FIELD =
            MixinReflectionSupport.findDeclaredFieldSafe(JOB_CLASS, "timeTracker");
    private static final @Nullable Field TASK_VALUE_FIELD =
            MixinReflectionSupport.findDeclaredFieldSafe(TASK_PROGRESS_CLASS, "value");
    private static final @Nullable Method ADD_MAX_ITEMS_METHOD =
            MixinReflectionSupport.findDeclaredMethodSafe(
                    ELAPSED_TIME_TRACKER_CLASS, "addMaxItems", long.class, AEKeyType.class);

    private final Object job;
    private Iterator<? extends Map.Entry<IPatternDetails, ?>> rawIterator = Collections.emptyIterator();
    private @Nullable Map.Entry<IPatternDetails, ?> currentEntry;

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
                && TASK_VALUE_FIELD != null
                && ADD_MAX_ITEMS_METHOD != null;
    }

    public static boolean acceptsJob(@Nullable Object candidate) {
        return isAvailable() && candidate != null && JOB_CLASS.isInstance(candidate);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Iterator<BatchTaskHandle> taskIterator() {
        Object rawTasks = MixinReflectionSupport.getFieldValueSafe(TASKS_FIELD, job);
        if (rawTasks instanceof Map<?, ?> tasks) {
            rawIterator = (Iterator<? extends Map.Entry<IPatternDetails, ?>>)
                    (Iterator<?>) tasks.entrySet().iterator();
        } else {
            rawIterator = Collections.emptyIterator();
        }
        currentEntry = null;
        return this;
    }

    @Override
    public boolean hasNext() {
        return rawIterator.hasNext();
    }

    @Override
    public BatchTaskHandle next() {
        currentEntry = rawIterator.next();
        return this;
    }

    @Override
    public void remove() {
        rawIterator.remove();
        currentEntry = null;
    }

    @Override
    public IPatternDetails details() {
        return requireCurrentEntry().getKey();
    }

    @Override
    public long getValue() {
        return MixinReflectionSupport.getLongFieldSafe(
                TASK_VALUE_FIELD, requireCurrentEntry().getValue(), 0L);
    }

    @Override
    public void setValue(long value) {
        MixinReflectionSupport.setLongFieldSafe(
                TASK_VALUE_FIELD, requireCurrentEntry().getValue(), value,
                "set NeoECO batch task progress");
    }

    @Override
    public ListCraftingInventory waitingFor() {
        Object waitingFor = MixinReflectionSupport.getFieldValueSafe(WAITING_FOR_FIELD, job);
        if (waitingFor instanceof ListCraftingInventory inventory) {
            return inventory;
        }
        throw new IllegalStateException("NeoECO crafting job has no compatible waitingFor inventory");
    }

    @Override
    public void addContainerMaxItems(long count, AEKeyType type) {
        Object tracker = MixinReflectionSupport.getFieldValueSafe(TIME_TRACKER_FIELD, job);
        if (tracker == null) {
            throw new IllegalStateException("NeoECO crafting job has no elapsed-time tracker");
        }
        MixinReflectionSupport.invokeMethodSafe(
                ADD_MAX_ITEMS_METHOD, tracker, "add NeoECO batch container items", count, type);
    }

    private Map.Entry<IPatternDetails, ?> requireCurrentEntry() {
        if (currentEntry == null) {
            throw new IllegalStateException("No current NeoECO crafting task");
        }
        return currentEntry;
    }
}
