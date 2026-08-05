package com.moakiee.thunderbolt.api.crafting.planner;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe registry for terminal planners. Higher priority planners run first. */
public final class CraftingPlannerRegistry {

    private static final AtomicLong ORDER = new AtomicLong();
    private static final CopyOnWriteArrayList<Entry> ENTRIES = new CopyOnWriteArrayList<>();

    private CraftingPlannerRegistry() {
    }

    public static Registration register(String id, int priority, ICraftingPlanner planner) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(planner, "planner");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (ENTRIES.stream().anyMatch(entry -> entry.id.equals(id))) {
            throw new IllegalStateException("Planner already registered: " + id);
        }
        var entry = new Entry(id, priority, ORDER.getAndIncrement(), planner);
        ENTRIES.add(entry);
        return new Registration(entry);
    }

    public static List<RegisteredPlanner> planners() {
        return ENTRIES.stream()
                .sorted(Comparator.comparingInt(Entry::priority).reversed()
                        .thenComparingLong(Entry::order))
                .map(entry -> new RegisteredPlanner(entry.id, entry.priority, entry.planner))
                .toList();
    }

    public record RegisteredPlanner(String id, int priority, ICraftingPlanner planner) {
    }

    public static final class Registration implements AutoCloseable {
        private Entry entry;

        private Registration(Entry entry) {
            this.entry = entry;
        }

        @Override
        public void close() {
            var current = entry;
            if (current != null) {
                ENTRIES.remove(current);
                entry = null;
            }
        }
    }

    private record Entry(String id, int priority, long order, ICraftingPlanner planner) {
    }
}
