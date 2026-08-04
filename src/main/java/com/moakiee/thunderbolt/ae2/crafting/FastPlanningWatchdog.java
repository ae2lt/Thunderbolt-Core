package com.moakiee.thunderbolt.ae2.crafting;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.moakiee.thunderbolt.core.planner.PlanningDiagnostics;

/**
 * Diagnostic watchdog for the fast crafting planner.
 *
 * <p>Each fast-path attempt registers the calculating thread here for its duration. A single shared
 * daemon thread ticks once a second and, for any attempt running longer than {@link #WARN_AFTER_MS},
 * logs at WARN the request label, the latest already-collected graph/planner counters, and a full stack
 * trace of the (otherwise unresponsive) calculating thread. Comparing consecutive dumps instantly
 * distinguishes a tight loop (identical stack) from runaway re-simulation, without guessing. The
 * watchdog only observes; it never interrupts or recomputes diagnostics.
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
        Watch watch = ACTIVE.remove(Thread.currentThread());
        if (watch == null) {
            return;
        }
        long elapsed = System.currentTimeMillis() - watch.startMs;
        if (elapsed >= WARN_AFTER_MS) {
            LOG.warn("[thunderbolt] SLOW crafting calc completed after {}ms\n    {}{}",
                    elapsed, watch.label, formatDiagnostics(watch));
        }
    }

    /** Records already-computed planner facts for the active calculation; performs no graph work. */
    public static void record(PlanningDiagnostics diagnostics) {
        Watch watch = ACTIVE.get(Thread.currentThread());
        if (watch != null) {
            watch.latestDiagnostics = diagnostics;
        }
    }

    /** Records the adapter's graph-export phase separately from core planning. */
    public static void recordGraphBuild(long nanos) {
        Watch watch = ACTIVE.get(Thread.currentThread());
        if (watch != null) {
            watch.latestGraphBuildNanos = Math.max(0L, nanos);
        }
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
                .append(formatDiagnostics(watch))
                .append("    thread '").append(watch.thread.getName()).append("' stack (")
                .append(stack.length).append(" frames):\n");
        for (StackTraceElement element : stack) {
            sb.append("\tat ").append(element).append('\n');
        }
        LOG.warn(sb.toString());
    }

    private static String formatDiagnostics(Watch watch) {
        PlanningDiagnostics diagnostics = watch.latestDiagnostics;
        if (diagnostics == null) {
            return watch.latestGraphBuildNanos <= 0L
                    ? ""
                    : "\n    last adapter graph export: "
                            + nanosToMillis(watch.latestGraphBuildNanos) + "ms\n";
        }
        return "\n    last adapter graph export: " + nanosToMillis(watch.latestGraphBuildNanos) + "ms"
                + "\n    last planner: work=" + diagnostics.reachableWorkEstimate()
                + ", graph=" + diagnostics.reachableItems() + " items/"
                + diagnostics.reachablePatterns() + " patterns/"
                + diagnostics.inputEdges() + " inputs"
                + ", contended=" + diagnostics.contendedOutputs()
                + ", cuts=" + diagnostics.cycleCuts()
                + ", seedOrdered=" + diagnostics.seedOrdered()
                + ", search=" + diagnostics.consumedSearchBudget() + "/"
                + diagnostics.configuredSearchBudget()
                + ", resolution=" + diagnostics.consumedResolutionBudget() + "/"
                + diagnostics.configuredResolutionBudget()
                + ", fallback=" + diagnostics.consumedFallbackBudget() + "/"
                + diagnostics.configuredFallbackBudget()
                + ", runs=" + diagnostics.planRuns()
                + ", compiled/reused=" + diagnostics.compiledOrientations() + "/"
                + diagnostics.reusedCompilations()
                + ", hot=" + diagnostics.hotNodeVisits()
                + ", dynamic=" + diagnostics.dynamicCapacityEvaluations()
                + ", equivalentPruned=" + diagnostics.equivalentRoutesPruned()
                + ", memoHits=" + diagnostics.failureMemoHits()
                + ", frontierPeak=" + diagnostics.frontierPeak()
                + ", cutoff(search/resolution/fallback)=" + diagnostics.searchCutoff()
                + "/" + diagnostics.resolutionCutoff()
                + "/" + diagnostics.fallbackCutoff()
                + ", phasesMs=" + nanosToMillis(diagnostics.graphCompileNanos()) + "/"
                + nanosToMillis(diagnostics.linearPassNanos()) + "/"
                + nanosToMillis(diagnostics.searchNanos())
                + ", totalMs=" + nanosToMillis(diagnostics.totalNanos()) + '\n';
    }

    private static long nanosToMillis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private static final class Watch {
        final Thread thread;
        final String label;
        final long startMs;
        volatile long lastReportMs;
        volatile PlanningDiagnostics latestDiagnostics;
        volatile long latestGraphBuildNanos;

        Watch(Thread thread, String label, long startMs) {
            this.thread = thread;
            this.label = label;
            this.startMs = startMs;
            this.lastReportMs = 0L;
        }
    }
}
