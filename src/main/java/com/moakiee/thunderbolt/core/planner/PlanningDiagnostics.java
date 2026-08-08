package com.moakiee.thunderbolt.core.planner;

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
   long totalNanos
) {
   public static PlanningDiagnostics empty() {
      return new PlanningDiagnostics(0, 0, 0, 0, 0, 0, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, false, false, 0L, 0L, 0L, 0L);
   }
}
