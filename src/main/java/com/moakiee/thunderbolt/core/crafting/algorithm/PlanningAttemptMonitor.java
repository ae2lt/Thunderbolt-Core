package com.moakiee.thunderbolt.core.crafting.algorithm;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.ResourceLocation;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;

/** Generic per-candidate computation budget, cooperative exit grace and diagnostics. */
final class PlanningAttemptMonitor implements PlanningAttemptContext, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("thunderbolt-crafting-planning");

    private static final long DEFAULT_WARN_MS = Math.max(0L, Long.getLong(
            "thunderbolt.planningWarnMs",
            Long.getLong("thunderbolt.watchdogMs", 2_000L)));
    private static final long DEFAULT_TIMEOUT_MS = Math.max(1L, Long.getLong(
            "thunderbolt.planningTimeoutMs", 3_000L));
    private static final long DEFAULT_STOP_GRACE_MS = Math.max(0L, Long.getLong(
            "thunderbolt.planningStopGraceMs", 1_000L));

    private static final int ACTIVE = 0;
    private static final int BUDGET_EXPIRED = 1;
    private static final int EXTERNAL_EXIT_REQUESTED = 2;
    private static final int HARD_TIMED_OUT = 3;
    private static final int CLOSED = 4;

    private static final ScheduledExecutorService EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                var thread = new Thread(runnable, "thunderbolt-planning-monitor");
                thread.setDaemon(true);
                return thread;
            });
    private final ResourceLocation engineId;
    private final String label;
    private final Thread calculationThread;
    private final long startedNanos;
    private final long deadlineNanos;
    private final long hardDeadlineNanos;
    private final long stopGraceMs;
    private final Runnable hardTimeoutAction;
    private final AtomicInteger state = new AtomicInteger(ACTIVE);
    private final ScheduledFuture<?> warningTask;
    private final ScheduledFuture<?> budgetExpiryTask;
    private final ScheduledFuture<?> hardTimeoutTask;
    private volatile ScheduledFuture<?> externalCancellationHardTimeoutTask;

    private volatile PlanningDiagnosticSnapshot latest = PlanningDiagnosticSnapshot.phase("starting");
    private volatile boolean timeoutObserved;
    private volatile boolean hardTimeoutObserved;

    private PlanningAttemptMonitor(
            ResourceLocation engineId,
            String label,
            long warnMs,
            long timeoutMs,
            long stopGraceMs,
            Runnable hardTimeoutAction) {
        this.engineId = engineId;
        this.label = label;
        this.calculationThread = Thread.currentThread();
        this.startedNanos = System.nanoTime();
        this.deadlineNanos = saturatedDeadline(startedNanos, timeoutMs);
        this.hardDeadlineNanos = saturatedDeadline(deadlineNanos, stopGraceMs);
        this.stopGraceMs = stopGraceMs;
        this.hardTimeoutAction = hardTimeoutAction;
        this.warningTask = warnMs <= 0L || warnMs >= timeoutMs
                ? null
                : EXECUTOR.schedule(this::warnSlow, warnMs, TimeUnit.MILLISECONDS);
        this.budgetExpiryTask = EXECUTOR.schedule(
                this::expireBudget, timeoutMs, TimeUnit.MILLISECONDS);
        this.hardTimeoutTask = EXECUTOR.schedule(
                this::hardTimeout, saturatedAdd(timeoutMs, stopGraceMs), TimeUnit.MILLISECONDS);
    }

    static PlanningAttemptMonitor start(
            ResourceLocation engineId, String label, Runnable hardTimeoutAction) {
        return new PlanningAttemptMonitor(
                engineId, label, DEFAULT_WARN_MS, DEFAULT_TIMEOUT_MS, DEFAULT_STOP_GRACE_MS,
                hardTimeoutAction);
    }

    static PlanningAttemptMonitor startForTest(
            ResourceLocation engineId,
            String label,
            long warnMs,
            long timeoutMs,
            long stopGraceMs) {
        return new PlanningAttemptMonitor(
                engineId, label, warnMs, timeoutMs, stopGraceMs, () -> { });
    }

    static PlanningAttemptMonitor startForTest(
            ResourceLocation engineId,
            String label,
            long warnMs,
            long timeoutMs,
            long stopGraceMs,
            Runnable hardTimeoutAction) {
        return new PlanningAttemptMonitor(
                engineId, label, warnMs, timeoutMs, stopGraceMs, hardTimeoutAction);
    }

    @Override
    public long deadlineNanos() {
        return deadlineNanos;
    }

    @Override
    public void checkpoint() {
        int current = state.get();
        long now = System.nanoTime();
        if (current == HARD_TIMED_OUT || now - hardDeadlineNanos >= 0L) {
            markHardTimeout();
            throw new PlanningExitException("planning candidate exceeded its stop grace: " + engineId);
        }
        if (current == BUDGET_EXPIRED || current == EXTERNAL_EXIT_REQUESTED
                || now - deadlineNanos >= 0L) {
            if (state.compareAndSet(ACTIVE, BUDGET_EXPIRED)) {
                timeoutObserved = true;
            }
            throw new PlanningExitException("planning candidate must exit: " + engineId);
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("crafting calculation interrupted");
        }
    }

    /** Atomically accepts a completed result during the grace period and closes its deadlines. */
    void acceptReturnedResult() {
        while (true) {
            int current = state.get();
            if (current == HARD_TIMED_OUT || System.nanoTime() - hardDeadlineNanos >= 0L) {
                markHardTimeout();
                throw new PlanningExitException(
                        "planning candidate exceeded its stop grace: " + engineId);
            }
            if (current == EXTERNAL_EXIT_REQUESTED) {
                throw new PlanningExitException("planning candidate was cancelled: " + engineId);
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("crafting calculation interrupted");
            }
            if (current == CLOSED || state.compareAndSet(current, CLOSED)) {
                cancelScheduledTasks();
                return;
            }
        }
    }

    @Override
    public void report(PlanningDiagnosticSnapshot snapshot) {
        if (snapshot != null && state.get() == ACTIVE) {
            latest = snapshot;
        }
    }

    boolean timedOut() {
        return timeoutObserved;
    }

    boolean hardTimedOut() {
        return hardTimeoutObserved;
    }

    /**
     * Forwards an outer cancellation as a cooperative exit request. The caller detaches
     * immediately, but the candidate gets its own grace period before hard interruption.
     */
    void requestExitForExternalCancellation() {
        while (true) {
            int current = state.get();
            if (current == CLOSED || current == HARD_TIMED_OUT) {
                return;
            }
            if (current == EXTERNAL_EXIT_REQUESTED) {
                return;
            }
            if (state.compareAndSet(current, EXTERNAL_EXIT_REQUESTED)) {
                break;
            }
        }
        scheduleExternalCancellationHardTimeout();
    }

    private void warnSlow() {
        if (state.get() != ACTIVE) {
            return;
        }
        runDiagnostic(() -> LOG.warn(
                "[Thunderbolt Core] slow planning candidate: engine={} elapsedMs={} {}\n{}",
                engineId, elapsedMillis(), label, diagnosticDump(true)));
    }

    private void expireBudget() {
        if (!state.compareAndSet(ACTIVE, BUDGET_EXPIRED)) {
            return;
        }
        timeoutObserved = true;
        runDiagnostic(() -> LOG.warn(
                "[Thunderbolt Core] planning candidate exhausted its computation budget; "
                        + "waiting for cooperative exit: engine={} elapsedMs={} {}\n{}",
                engineId, elapsedMillis(), label, diagnosticDump(true)));
    }

    private void hardTimeout() {
        if (!markHardTimeout()) {
            return;
        }
        runDiagnostic(() -> LOG.error(
                "[Thunderbolt Core] planning candidate did not exit within its grace period; "
                        + "interrupting it now: engine={} elapsedMs={} {}\n{}",
                engineId, elapsedMillis(), label, diagnosticDump(true)));
    }

    private boolean markHardTimeout() {
        while (true) {
            int current = state.get();
            if (current == CLOSED || current == HARD_TIMED_OUT) {
                return false;
            }
            if (state.compareAndSet(current, HARD_TIMED_OUT)) {
                timeoutObserved = true;
                try {
                    hardTimeoutAction.run();
                } finally {
                    calculationThread.interrupt();
                    // Publish completion only after quarantine and interruption are in effect.
                    // The owning calculation thread can then detach without repeating either.
                    hardTimeoutObserved = true;
                }
                return true;
            }
        }
    }

    private String diagnosticDump(boolean includeStack) {
        var snapshot = latest;
        var out = new StringBuilder(256)
                .append("    phase=").append(snapshot.phase());
        for (Map.Entry<String, Long> metric : snapshot.metrics().entrySet()) {
            out.append(' ').append(metric.getKey()).append('=').append(metric.getValue());
        }
        if (includeStack) {
            var stack = calculationThread.getStackTrace();
            out.append("\n    thread '").append(calculationThread.getName()).append("' stack:");
            for (StackTraceElement element : stack) {
                out.append("\n\tat ").append(element);
            }
        }
        return out.toString();
    }

    private long elapsedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
    }

    @Override
    public void close() {
        int previous = state.getAndSet(CLOSED);
        cancelScheduledTasks();
        if (previous == HARD_TIMED_OUT && Thread.currentThread() == calculationThread) {
            Thread.interrupted();
        }
    }

    private void cancelScheduledTasks() {
        if (warningTask != null) {
            warningTask.cancel(false);
        }
        budgetExpiryTask.cancel(false);
        hardTimeoutTask.cancel(false);
        var externalHardTimeout = externalCancellationHardTimeoutTask;
        if (externalHardTimeout != null) {
            externalHardTimeout.cancel(false);
        }
    }

    private void scheduleExternalCancellationHardTimeout() {
        final ScheduledFuture<?> task;
        try {
            task = EXECUTOR.schedule(this::hardTimeout, stopGraceMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException schedulingFailure) {
            // Losing the cancellation timer must not leave an abandoned candidate running
            // without a hard boundary.
            markHardTimeout();
            return;
        }
        externalCancellationHardTimeoutTask = task;
        if (state.get() == CLOSED) {
            task.cancel(false);
        }
    }

    private static void runDiagnostic(Runnable diagnostic) {
        Thread.ofVirtual().name("thunderbolt-planning-diagnostics").start(diagnostic);
    }

    private static long saturatedDeadline(long started, long timeoutMs) {
        long nanos;
        try {
            nanos = Math.multiplyExact(timeoutMs, 1_000_000L);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
        long deadline = started + nanos;
        return deadline < started ? Long.MAX_VALUE : deadline;
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }
}
