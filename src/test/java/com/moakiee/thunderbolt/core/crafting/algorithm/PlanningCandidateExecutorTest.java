package com.moakiee.thunderbolt.core.crafting.algorithm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

import com.moakiee.thunderbolt.api.crafting.PlanningExitException;

class PlanningCandidateExecutorTest {
    @Test
    void externalCancellationIsExitInsideAndCancellationOutside() throws Exception {
        var engineId = id("external_cancel");
        var started = new CountDownLatch(1);
        var internalExit = new CompletableFuture<Boolean>();
        var outerCancellation = new CompletableFuture<Boolean>();
        var discarded = new CompletableFuture<Integer>();

        var caller = Thread.ofPlatform().start(() -> {
            try {
                PlanningCandidateExecutor.executeForTest(
                        engineId,
                        "external cancellation test",
                        context -> {
                            started.countDown();
                            while (true) {
                                try {
                                    context.checkpoint();
                                } catch (PlanningExitException expected) {
                                    internalExit.complete(true);
                                    return 42;
                                }
                                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                            }
                        },
                        () -> { },
                        discarded::complete,
                        5_000,
                        5_000);
                outerCancellation.complete(false);
            } catch (InterruptedException expected) {
                outerCancellation.complete(true);
            } catch (Throwable failure) {
                internalExit.completeExceptionally(failure);
                outerCancellation.completeExceptionally(failure);
            }
        });

        assertTrue(started.await(2, TimeUnit.SECONDS));
        caller.interrupt();

        assertTrue(internalExit.get(2, TimeUnit.SECONDS));
        assertTrue(outerCancellation.get(2, TimeUnit.SECONDS));
        assertEquals(42, discarded.get(2, TimeUnit.SECONDS));
        caller.join(2_000);
        assertFalse(caller.isAlive());
        assertFalse(
                PlanningCandidateExecutor.isQuarantined(engineId),
                "external cancellation alone must not quarantine the engine");
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (PlanningCandidateExecutor.isQuarantined(engineId)) {
                Thread.onSpinWait();
            }
        });
    }

    @Test
    void usableResultReturnedDuringGraceIsAcceptedWithoutInterrupt() {
        var interrupted = new AtomicBoolean();
        var result = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> PlanningCandidateExecutor.executeForTest(
                        id("grace_result"),
                        "grace result test",
                        context -> {
                            Thread.sleep(80L);
                            assertTrue(System.nanoTime() - context.deadlineNanos() >= 0L);
                            org.junit.jupiter.api.Assertions.assertThrows(
                                    PlanningExitException.class, context::checkpoint);
                            interrupted.set(Thread.currentThread().isInterrupted());
                            return 7;
                        },
                        () -> { },
                        20,
                        1_000));

        assertEquals(PlanningCandidateExecutor.Status.SUCCESS, result.status());
        assertEquals(7, result.value());
        assertFalse(interrupted.get());
    }

    @Test
    void hardTimeoutDetachesNonCooperativeCandidateAndAllowsNextCandidate() {
        var stuckId = id("ignores_interrupt");
        var nextId = id("next");
        var stuckStillRunning = new AtomicBoolean();
        var schedulerYields = new AtomicInteger();

        var hardTimedOut = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> PlanningCandidateExecutor.executeForTest(
                        stuckId,
                        "non-cooperative test",
                        context -> {
                            stuckStillRunning.set(true);
                            long finishAt = System.nanoTime() + Duration.ofMillis(250).toNanos();
                            while (System.nanoTime() - finishAt < 0L) {
                                Thread.onSpinWait();
                            }
                            stuckStillRunning.set(false);
                            return 1;
                        },
                        schedulerYields::incrementAndGet,
                        20,
                        30));

        assertEquals(PlanningCandidateExecutor.Status.HARD_TIMEOUT, hardTimedOut.status());
        assertTrue(stuckStillRunning.get(), "the ignored interrupt must not hold up the caller");
        assertTrue(PlanningCandidateExecutor.isQuarantined(stuckId));
        assertTrue(schedulerYields.get() > 0);

        var next = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> PlanningCandidateExecutor.executeForTest(
                        nextId, "next test", context -> 42, () -> { }, 100, 100));
        assertEquals(PlanningCandidateExecutor.Status.SUCCESS, next.status());
        assertEquals(42, next.value());

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (PlanningCandidateExecutor.isQuarantined(stuckId)) {
                Thread.onSpinWait();
            }
        });
        assertFalse(stuckStillRunning.get());
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("thunderbolt_test", path);
    }
}
