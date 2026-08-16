package com.moakiee.thunderbolt.core.crafting.algorithm;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import net.minecraft.resources.ResourceLocation;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.core.crafting.planner.PlanningCancellation;

/**
 * Isolates an engine candidate so a non-cooperative implementation cannot block AE2's worker.
 * The monitor first publishes a cooperative deadline, interrupts only after the exit grace, then
 * the caller detaches and quarantines an invocation that still does not return.
 */
public final class PlanningCandidateExecutor {
    private static final long POLL_MS = 1L;

    private static final java.util.concurrent.ExecutorService EXECUTOR =
            Executors.newCachedThreadPool(runnable -> {
                var thread = new Thread(runnable, "thunderbolt-planning-candidate");
                thread.setDaemon(true);
                return thread;
            });

    /** Number of detached invocations that have not actually returned yet, keyed by engine. */
    private static final ConcurrentHashMap<ResourceLocation, AtomicInteger> QUARANTINED =
            new ConcurrentHashMap<>();

    private PlanningCandidateExecutor() {
    }

    public static boolean isQuarantined(ResourceLocation engineId) {
        var count = QUARANTINED.get(engineId);
        return count != null && count.get() > 0;
    }

    public static <T> Result<T> execute(
            ResourceLocation engineId,
            String label,
            Work<T> work,
            SchedulerYield schedulerYield) throws InterruptedException {
        return execute(
                engineId, label, work, schedulerYield, ignored -> { },
                PlanningAttemptMonitor::start);
    }

    public static <T> Result<T> execute(
            ResourceLocation engineId,
            String label,
            Work<T> work,
            SchedulerYield schedulerYield,
            Consumer<T> discard) throws InterruptedException {
        return execute(engineId, label, work, schedulerYield, discard, PlanningAttemptMonitor::start);
    }

    static <T> Result<T> executeForTest(
            ResourceLocation engineId,
            String label,
            Work<T> work,
            SchedulerYield schedulerYield,
            long timeoutMs,
            long stopGraceMs) throws InterruptedException {
        return executeForTest(
                engineId, label, work, schedulerYield, ignored -> { }, timeoutMs, stopGraceMs);
    }

    static <T> Result<T> executeForTest(
            ResourceLocation engineId,
            String label,
            Work<T> work,
            SchedulerYield schedulerYield,
            Consumer<T> discard,
            long timeoutMs,
            long stopGraceMs) throws InterruptedException {
        return execute(
                engineId,
                label,
                work,
                schedulerYield,
                discard,
                (id, taskLabel, hardTimeoutAction) -> PlanningAttemptMonitor.startForTest(
                        id, taskLabel, 0L, timeoutMs, stopGraceMs, hardTimeoutAction));
    }

    private static <T> Result<T> execute(
            ResourceLocation engineId,
            String label,
            Work<T> work,
            SchedulerYield schedulerYield,
            Consumer<T> discard,
            MonitorFactory monitorFactory) throws InterruptedException {
        if (isQuarantined(engineId)) {
            return new Result<>(Status.QUARANTINED, null, null);
        }

        var monitorReady = new CompletableFuture<PlanningAttemptMonitor>();
        var result = new CompletableFuture<T>();
        var quarantineRegistered = new AtomicBoolean();
        var discardRegistered = new AtomicBoolean();
        Runnable quarantine = () -> quarantineUntilReturnedOnce(
                engineId, result, quarantineRegistered);
        EXECUTOR.execute(() -> {
            var monitor = monitorFactory.start(engineId, label, quarantine);
            monitorReady.complete(monitor);
            try (monitor; var ignored = PlanningCancellation.bind(monitor)) {
                T value = work.run(monitor);
                try {
                    monitor.acceptReturnedResult();
                } catch (Throwable failure) {
                    try {
                        discard.accept(value);
                    } catch (Throwable cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                    throw failure;
                }
                result.complete(value);
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });

        PlanningAttemptMonitor monitor;
        try {
            monitor = awaitMonitor(monitorReady, schedulerYield);
        } catch (InterruptedException interrupted) {
            discardWhenReturnedOnce(result, discard, discardRegistered);
            monitorReady.thenAccept(PlanningAttemptMonitor::requestExitForExternalCancellation);
            throw interrupted;
        }

        try {
            while (true) {
                if (Thread.interrupted()) {
                    throw new InterruptedException("crafting calculation cancelled");
                }
                try {
                    T value = result.get(POLL_MS, TimeUnit.MILLISECONDS);
                    if (Thread.interrupted()) {
                        throw new InterruptedException("crafting calculation cancelled");
                    }
                    return new Result<>(Status.SUCCESS, value, null);
                } catch (TimeoutException ignored) {
                    if (monitor.hardTimedOut()) {
                        monitor.interruptCandidate();
                        quarantine.run();
                        return new Result<>(Status.HARD_TIMEOUT, null, null);
                    }
                    schedulerYield.run();
                } catch (ExecutionException failed) {
                    if (Thread.interrupted()) {
                        throw new InterruptedException("crafting calculation cancelled");
                    }
                    Throwable failure = failed.getCause();
                    return new Result<>(
                            monitor.hardTimedOut()
                                    ? Status.HARD_TIMEOUT
                                    : monitor.timedOut() ? Status.SOFT_TIMEOUT : Status.FAILED,
                            null,
                            failure);
                }
            }
        } catch (InterruptedException interrupted) {
            discardWhenReturnedOnce(result, discard, discardRegistered);
            monitor.requestExitForExternalCancellation();
            throw interrupted;
        }
    }

    private static PlanningAttemptMonitor awaitMonitor(
            CompletableFuture<PlanningAttemptMonitor> monitorReady,
            SchedulerYield schedulerYield) throws InterruptedException {
        while (true) {
            try {
                return monitorReady.get(POLL_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                schedulerYield.run();
            } catch (ExecutionException impossible) {
                throw new IllegalStateException("candidate monitor failed to start", impossible.getCause());
            }
        }
    }

    private static void quarantineUntilReturnedOnce(
            ResourceLocation engineId,
            CompletableFuture<?> result,
            AtomicBoolean quarantineRegistered) {
        if (!quarantineRegistered.compareAndSet(false, true)) {
            return;
        }
        var count = QUARANTINED.computeIfAbsent(engineId, ignored -> new AtomicInteger());
        count.incrementAndGet();
        result.whenComplete((ignoredResult, ignoredFailure) -> {
            if (count.decrementAndGet() == 0) {
                QUARANTINED.remove(engineId, count);
            }
        });
    }

    private static <T> void discardWhenReturnedOnce(
            CompletableFuture<T> result,
            Consumer<T> discard,
            AtomicBoolean discardRegistered) {
        if (discardRegistered.compareAndSet(false, true)) {
            result.thenAccept(discard);
        }
    }

    public enum Status {
        SUCCESS,
        FAILED,
        SOFT_TIMEOUT,
        HARD_TIMEOUT,
        QUARANTINED
    }

    public record Result<T>(Status status, T value, Throwable failure) {
    }

    @FunctionalInterface
    public interface Work<T> {
        T run(PlanningAttemptContext context) throws Throwable;
    }

    @FunctionalInterface
    public interface SchedulerYield {
        void run() throws InterruptedException;
    }

    @FunctionalInterface
    private interface MonitorFactory {
        PlanningAttemptMonitor start(
                ResourceLocation engineId, String label, Runnable hardTimeoutAction);
    }
}
