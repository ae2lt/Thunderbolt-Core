package com.moakiee.thunderbolt.ae2.crafting;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diagnostic watchdog for the fast crafting planner.
 *
 * <p>Each fast-path attempt registers the calculating thread here for its duration. A single shared
 * daemon thread ticks once a second and, for any attempt running longer than {@link #WARN_AFTER_MS},
 * logs at WARN the request label and a full stack trace of the (otherwise unresponsive) calculating
 * thread. Comparing consecutive dumps instantly distinguishes a tight loop (identical stack) from
 * runaway re-simulation, without guessing. The watchdog only observes; it never interrupts, so the
 * captured behavior matches what the user sees.
 *
 * <p>Thresholds are overridable via system properties:
 * {@code -Dthunderbolt.watchdogMs} (first warn) and {@code -Dthunderbolt.watchdogRepeatMs}.
 */
public final class FastPlanningWatchdog {
    private static final Logger LOG = LoggerFactory.getLogger("thunderbolt-fast-crafting");

    private static final long WARN_AFTER_MS = Long.getLong("thunderbolt.watchdogMs", 4_000L);
    private static final long REPEAT_MS = Long.getLong("thunderbolt.watchdogRepeatMs", 4_000L);

    private static final Map<Thread, Watch> ACTIVE = new ConcurrentHashMap<>();
    private static volatile ScheduledExecutorService exec;

    private FastPlanningWatchdog() {
    }

    public static void start(String label) {
        ensureTicker();
        ACTIVE.put(Thread.currentThread(), new Watch(Thread.currentThread(), label, System.currentTimeMillis()));
    }

    public static void stop() {
        ACTIVE.remove(Thread.currentThread());
    }

    private static void ensureTicker() {
        if (exec != null) {
            return;
        }
        synchronized (FastPlanningWatchdog.class) {
            if (exec != null) {
                return;
            }
            var service = Executors.newSingleThreadScheduledExecutor(runnable -> {
                var thread = new Thread(runnable, "thunderbolt-fastcraft-watchdog");
                thread.setDaemon(true);
                return thread;
            });
            service.scheduleWithFixedDelay(FastPlanningWatchdog::tick, 1, 1, TimeUnit.SECONDS);
            exec = service;
        }
    }

    private static void tick() {
        long now = System.currentTimeMillis();
        for (Watch watch : ACTIVE.values()) {
            long elapsed = now - watch.startMs;
            if (elapsed < WARN_AFTER_MS) {
                continue;
            }
            if (now - watch.lastReportMs < REPEAT_MS) {
                continue;
            }
            watch.lastReportMs = now;
            dump(watch, elapsed);
        }
    }

    private static void dump(Watch watch, long elapsed) {
        StackTraceElement[] stack = watch.thread.getStackTrace();
        var sb = new StringBuilder(512);
        sb.append("[thunderbolt] SLOW crafting calc still running after ").append(elapsed).append("ms\n")
                .append("    ").append(watch.label).append('\n')
                .append("    thread '").append(watch.thread.getName()).append("' stack (")
                .append(stack.length).append(" frames):\n");
        for (StackTraceElement element : stack) {
            sb.append("\tat ").append(element).append('\n');
        }
        LOG.warn(sb.toString());
    }

    private static final class Watch {
        final Thread thread;
        final String label;
        final long startMs;
        volatile long lastReportMs;

        Watch(Thread thread, String label, long startMs) {
            this.thread = thread;
            this.label = label;
            this.startMs = startMs;
            this.lastReportMs = 0L;
        }
    }
}
