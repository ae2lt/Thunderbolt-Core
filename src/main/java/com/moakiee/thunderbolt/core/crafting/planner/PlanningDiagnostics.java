package com.moakiee.thunderbolt.core.crafting.planner;

/**
 * Measurements exported by one {@link CraftPlannerV2} request.
 *
 * <p>All values are collected while the planner is already doing the corresponding work. Producing
 * this record never rebuilds the recipe graph or runs a second diagnostic plan, which makes it safe
 * to include in slow-planning logs.
 */
public record PlanningDiagnostics(
        int reachableWorkEstimate,
        int reachableItems,
        int reachablePatterns,
        int inputEdges,
        int contendedOutputs,
        int cycleCuts,
        boolean seedOrdered,
        int configuredSearchBudget,
        int consumedSearchBudget,
        int configuredResolutionBudget,
        int consumedResolutionBudget,
        int configuredFallbackBudget,
        int consumedFallbackBudget,
        int planRuns,
        int compiledOrientations,
        int reusedCompilations,
        int hotNodeVisits,
        int dynamicCapacityEvaluations,
        int equivalentRoutesPruned,
        int failureMemoHits,
        int frontierPeak,
        boolean searchCutoff,
        boolean resolutionCutoff,
        boolean fallbackCutoff,
        long graphCompileNanos,
        long linearPassNanos,
        long searchNanos,
        long totalNanos,
        int separatorWidthPeak,
        int lowWidthAttempts,
        int lowWidthSolved,
        int lowWidthInfeasible,
        int lowWidthCutoffs,
        int lowWidthIntegerNodes) {

    /**
     * Source- and binary-compatible constructor for callers compiled against the diagnostics shape
     * before the low-width counters were appended.
     */
    public PlanningDiagnostics(
            int reachableWorkEstimate,
            int reachableItems,
            int reachablePatterns,
            int inputEdges,
            int contendedOutputs,
            int cycleCuts,
            boolean seedOrdered,
            int configuredSearchBudget,
            int consumedSearchBudget,
            int configuredResolutionBudget,
            int consumedResolutionBudget,
            int configuredFallbackBudget,
            int consumedFallbackBudget,
            int planRuns,
            int compiledOrientations,
            int reusedCompilations,
            int hotNodeVisits,
            int dynamicCapacityEvaluations,
            int equivalentRoutesPruned,
            int failureMemoHits,
            int frontierPeak,
            boolean searchCutoff,
            boolean resolutionCutoff,
            boolean fallbackCutoff,
            long graphCompileNanos,
            long linearPassNanos,
            long searchNanos,
            long totalNanos) {
        this(
                reachableWorkEstimate,
                reachableItems,
                reachablePatterns,
                inputEdges,
                contendedOutputs,
                cycleCuts,
                seedOrdered,
                configuredSearchBudget,
                consumedSearchBudget,
                configuredResolutionBudget,
                consumedResolutionBudget,
                configuredFallbackBudget,
                consumedFallbackBudget,
                planRuns,
                compiledOrientations,
                reusedCompilations,
                hotNodeVisits,
                dynamicCapacityEvaluations,
                equivalentRoutesPruned,
                failureMemoHits,
                frontierPeak,
                searchCutoff,
                resolutionCutoff,
                fallbackCutoff,
                graphCompileNanos,
                linearPassNanos,
                searchNanos,
                totalNanos,
                0,
                0,
                0,
                0,
                0,
                0);
    }

    public static PlanningDiagnostics empty() {
        return new PlanningDiagnostics(
                0, 0, 0, 0, 0, 0, false,
                0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, false, false, false,
                0L, 0L, 0L, 0L,
                0, 0, 0, 0, 0, 0);
    }
}
