package com.moakiee.thunderbolt.core.planner.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.moakiee.thunderbolt.core.planner.CraftGraph;
import com.moakiee.thunderbolt.core.planner.CraftPlan;

class ReferenceCapabilityRunnerTest {
    private static final ReferenceScenario FEASIBLE = new ReferenceScenario(
            "classification/feasible", ReferenceCapability.SINGLE_DAG,
            ReferenceMaterialMode.MINIMUM, 1, CraftGraph.<String>builder().build(),
            "target", 1, true, java.util.List.of(), Map.of(), ignored -> true);
    private static final ReferenceScenario MISSING = new ReferenceScenario(
            "classification/missing", ReferenceCapability.SINGLE_DAG,
            ReferenceMaterialMode.MISSING, 1, CraftGraph.<String>builder().build(),
            "target", 1, false, java.util.List.of(Map.of("raw", 2L)), Map.of(), ignored -> true);
    private static final ReferenceCapabilityRunner RUNNER = new ReferenceCapabilityRunner(
            Duration.ofMillis(50), Duration.ofMillis(50));

    @Test
    void onlyProductionPathSuccessCountsAsSupported() {
        var result = RUNNER.run(planner(true, feasiblePlan()), FEASIBLE);

        assertEquals(ReferenceSupportStatus.SUPPORTED, result.status());
        assertTrue(result.supported());
    }

    @Test
    void checkRejectionAndFalseNegativeStayDistinct() {
        var falseNegative = RUNNER.run(planner(false, feasiblePlan()), FEASIBLE);
        var rejected = RUNNER.run(planner(false, infeasiblePlan(Map.of("raw", 1L))), FEASIBLE);

        assertEquals(ReferenceSupportStatus.FALSE_NEGATIVE, falseNegative.status());
        assertEquals(ReferenceSupportStatus.CHECK_REJECTED, rejected.status());
    }

    @Test
    void lateDeclineIsNotAnError() {
        var result = RUNNER.run(planner(true, declinedPlan()), FEASIBLE);

        assertEquals(ReferenceSupportStatus.ATTEMPT_DECLINED, result.status());
    }

    @Test
    void wrongHandledResultIsFalsePositive() {
        var result = RUNNER.run(planner(true, infeasiblePlan(Map.of("raw", 1L))), FEASIBLE);

        assertEquals(ReferenceSupportStatus.FALSE_POSITIVE, result.status());
    }

    @Test
    void validButNonMinimalMissingReportsOverhead() {
        var result = RUNNER.run(planner(true, infeasiblePlan(Map.of("raw", 4L))), MISSING);

        assertEquals(ReferenceSupportStatus.SUPPORTED, result.status());
        assertEquals(2.0D, result.missingOverhead());
    }

    @Test
    void underReportedMissingIsFalsePositive() {
        var result = RUNNER.run(planner(true, infeasiblePlan(Map.of("raw", 1L))), MISSING);

        assertEquals(ReferenceSupportStatus.FALSE_POSITIVE, result.status());
    }

    @Test
    void exceptionAndCooperativeTimeoutStayDistinct() {
        ReferencePlanner error = new ReferencePlanner() {
            @Override
            public boolean check(ReferenceScenario scenario) {
                return true;
            }

            @Override
            public CraftPlan<String> plan(ReferenceScenario scenario) {
                throw new IllegalStateException("boom");
            }
        };
        ReferencePlanner timeout = new ReferencePlanner() {
            @Override
            public boolean check(ReferenceScenario scenario) {
                return true;
            }

            @Override
            public CraftPlan<String> plan(ReferenceScenario scenario) throws InterruptedException {
                Thread.sleep(10_000L);
                return feasiblePlan();
            }
        };

        assertEquals(ReferenceSupportStatus.ENGINE_ERROR, RUNNER.run(error, FEASIBLE).status());
        assertEquals(ReferenceSupportStatus.ENGINE_TIMEOUT, RUNNER.run(timeout, FEASIBLE).status());
    }

    @Test
    void nonCooperativeTimeoutIsReportedSeparately() {
        ReferencePlanner planner = new ReferencePlanner() {
            @Override
            public boolean check(ReferenceScenario scenario) {
                return true;
            }

            @Override
            public CraftPlan<String> plan(ReferenceScenario scenario) {
                long until = System.nanoTime() + Duration.ofMillis(500).toNanos();
                while (System.nanoTime() < until) {
                    // Deliberately ignore interruption to exercise quarantine classification.
                }
                return feasiblePlan();
            }
        };

        var result = RUNNER.run(planner, FEASIBLE);

        assertEquals(ReferenceSupportStatus.NON_COOPERATIVE_TIMEOUT, result.status());
    }

    private static ReferencePlanner planner(boolean check, CraftPlan<String> plan) {
        return new ReferencePlanner() {
            @Override
            public boolean check(ReferenceScenario scenario) {
                return check;
            }

            @Override
            public CraftPlan<String> plan(ReferenceScenario scenario) {
                return plan;
            }
        };
    }

    private static CraftPlan<String> feasiblePlan() {
        return new CraftPlan<>(true, true, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 1, false);
    }

    private static CraftPlan<String> infeasiblePlan(Map<String, Long> missing) {
        return new CraftPlan<>(true, false, Map.of(), Map.of(), Map.of(), missing, Map.of(), 1, false);
    }

    private static CraftPlan<String> declinedPlan() {
        return new CraftPlan<>(false, false, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0, false);
    }
}
