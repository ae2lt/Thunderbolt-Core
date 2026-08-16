package com.moakiee.thunderbolt.core.crafting.algorithm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

import com.moakiee.thunderbolt.api.crafting.PlanningExitException;

class PlanningAttemptMonitorTest {
    @Test
    void budgetExpiryIsCooperativeAndHardDeadlineInterruptsCandidate() throws Exception {
        var engineId = ResourceLocation.fromNamespaceAndPath("thunderbolt_test", "slow");
        var monitorReady = new CompletableFuture<PlanningAttemptMonitor>();
        var outcome = new CompletableFuture<Outcome>();
        var worker = Thread.ofPlatform().start(() -> {
            var monitor = PlanningAttemptMonitor.startForTest(
                    engineId,
                    "monitor test",
                    0,
                    30,
                    200);
            monitorReady.complete(monitor);
            boolean timeoutThrown = false;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
                try {
                    monitor.checkpoint();
                } catch (PlanningExitException expected) {
                    timeoutThrown = true;
                }
                outcome.complete(new Outcome(
                        monitor.timedOut(), timeoutThrown, Thread.currentThread().isInterrupted()));
            } catch (Throwable failure) {
                outcome.completeExceptionally(failure);
            } finally {
                monitor.close();
            }
        });

        var monitor = monitorReady.get(1, TimeUnit.SECONDS);
        waitUntil(monitor::timedOut);
        assertFalse(worker.isInterrupted(), "budget expiry must not interrupt the candidate");
        waitUntil(monitor::hardTimedOut);
        var beforeClose = outcome.get(2, TimeUnit.SECONDS);
        worker.join(2_000);

        assertTrue(beforeClose.timedOut());
        assertTrue(beforeClose.timeoutThrown());
        assertTrue(beforeClose.interruptedBeforeClose());
        assertFalse(worker.isAlive());
        assertFalse(worker.isInterrupted());
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0L) {
            Thread.sleep(1L);
        }
        assertTrue(condition.getAsBoolean());
    }

    private record Outcome(
            boolean timedOut,
            boolean timeoutThrown,
            boolean interruptedBeforeClose) {
    }
}
