package com.moakiee.thunderbolt.core.planner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.Function;

public final class ReusableStockMatcher {
   public static <K> ReusableStockMatcher.Result<K> allocate(
      Map<ReusableStockKey<K>, Long> available,
      Map<ReusableStockRouteKey<K>, Long> demand,
      Function<ReusableStockRouteKey<K>, ? extends Iterable<K>> candidates
   ) {
      List<Entry<ReusableStockKey<K>, Long>> actual = positiveEntries(available);
      List<Entry<ReusableStockRouteKey<K>, Long>> routes = positiveEntries(demand);
      if (routes.isEmpty()) {
         return new ReusableStockMatcher.Result<>(true, Map.of());
      } else if (actual.isEmpty()) {
         return new ReusableStockMatcher.Result<>(false, Map.of());
      } else {
         int source = 0;
         int routeOffset = 1;
         int actualOffset = routeOffset + routes.size();
         int sink = actualOffset + actual.size();
         ReusableStockMatcher.LongCapacityFlow flow = new ReusableStockMatcher.LongCapacityFlow(sink + 1);
         LinkedHashMap<ReusableStockRouteKey<K>, Set<K>> acceptedByRoute = new LinkedHashMap<>();

         for (int i = 0; i < routes.size(); i++) {
            ReusableStockRouteKey<K> route = routes.get(i).getKey();
            LinkedHashSet<K> accepted = new LinkedHashSet<>();
            Iterable<K> routeCandidates = (Iterable<K>)candidates.apply(route);
            if (routeCandidates != null) {
               for (K candidate : routeCandidates) {
                  if (candidate != null) {
                     accepted.add(candidate);
                  }
               }
            }

            acceptedByRoute.put(route, accepted);
         }

         ArrayList<ReusableStockMatcher.AssignmentEdge<K>> assignmentEdges = new ArrayList<>();

         for (int i = 0; i < actual.size(); i++) {
            flow.addEdge(actualOffset + i, sink, actual.get(i).getValue());
         }

         ArrayList<ReusableStockMatcher.LongCapacityFlow.Edge> demandEdges = new ArrayList<>(routes.size());

         for (int i = 0; i < routes.size(); i++) {
            ReusableStockRouteKey<K> route = routes.get(i).getKey();
            demandEdges.add(flow.addEdge(source, routeOffset + i, routes.get(i).getValue()));

            for (int exactPass = 0; exactPass < 2; exactPass++) {
               for (int actualIndex = 0; actualIndex < actual.size(); actualIndex++) {
                  Entry<ReusableStockKey<K>, Long> actualEntry = actual.get(actualIndex);
                  if (route.source().storageScope().equals(actualEntry.getKey().scope())) {
                     K actualKey = actualEntry.getKey().key();
                     if (acceptedByRoute.get(route).contains(actualKey)) {
                        boolean exact = route.plannedKey().equals(actualKey);
                        if (exactPass == 0 == exact) {
                           ReusableStockMatcher.LongCapacityFlow.Edge edge = flow.addEdge(routeOffset + i, actualOffset + actualIndex, Long.MAX_VALUE);
                           assignmentEdges.add(new ReusableStockMatcher.AssignmentEdge<>(route, actualKey, edge));
                        }
                     }
                  }
               }
            }
         }

         flow.maximize(source, sink);
         boolean feasible = demandEdges.stream().allMatch(edgex -> edgex.remaining == 0L);
         LinkedHashMap<ReusableStockAllocationKey<K>, Long> allocation = new LinkedHashMap<>();

         for (ReusableStockMatcher.AssignmentEdge<K> assignment : assignmentEdges) {
            long used = Long.MAX_VALUE - assignment.edge.remaining;
            if (used > 0L) {
               allocation.put(new ReusableStockAllocationKey<>(assignment.route, assignment.actualKey), Long.valueOf(used));
            }
         }

         return new ReusableStockMatcher.Result<>(feasible, allocation);
      }
   }

   private static <K> List<Entry<K, Long>> positiveEntries(Map<K, Long> input) {
      ArrayList<Entry<K, Long>> result = new ArrayList<>();

      for (Entry<K, Long> entry : input.entrySet()) {
         if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L) {
            result.add(Map.entry(entry.getKey(), entry.getValue()));
         }
      }

      return result;
   }

   private ReusableStockMatcher() {
   }

   private static record AssignmentEdge<K>(ReusableStockRouteKey<K> route, K actualKey, ReusableStockMatcher.LongCapacityFlow.Edge edge) {
   }

   private static final class LongCapacityFlow {
      private final List<List<ReusableStockMatcher.LongCapacityFlow.Edge>> graph;
      private int[] level;
      private int[] next;

      private LongCapacityFlow(int nodes) {
         this.graph = new ArrayList<>(nodes);

         for (int i = 0; i < nodes; i++) {
            this.graph.add(new ArrayList<>());
         }
      }

      private ReusableStockMatcher.LongCapacityFlow.Edge addEdge(int from, int to, long capacity) {
         ReusableStockMatcher.LongCapacityFlow.Edge forward = new ReusableStockMatcher.LongCapacityFlow.Edge(to, this.graph.get(to).size(), capacity);
         ReusableStockMatcher.LongCapacityFlow.Edge reverse = new ReusableStockMatcher.LongCapacityFlow.Edge(from, this.graph.get(from).size(), 0L);
         this.graph.get(from).add(forward);
         this.graph.get(to).add(reverse);
         return forward;
      }

      private void maximize(int source, int sink) {
         while (this.buildLevels(source, sink)) {
            this.next = new int[this.graph.size()];

            while (this.send(source, sink, Long.MAX_VALUE) > 0L) {
            }
         }
      }

      private boolean buildLevels(int source, int sink) {
         this.level = new int[this.graph.size()];
         Arrays.fill(this.level, -1);
         this.level[source] = 0;
         ArrayDeque<Integer> queue = new ArrayDeque<>();
         queue.add(source);

         while (!queue.isEmpty()) {
            int node = queue.removeFirst();

            for (ReusableStockMatcher.LongCapacityFlow.Edge edge : this.graph.get(node)) {
               if (edge.remaining > 0L && this.level[edge.to] < 0) {
                  this.level[edge.to] = this.level[node] + 1;
                  queue.addLast(edge.to);
               }
            }
         }

         return this.level[sink] >= 0;
      }

      private long send(int node, int sink, long limit) {
         if (node == sink) {
            return limit;
         } else {
            for (List<ReusableStockMatcher.LongCapacityFlow.Edge> edges = this.graph.get(node); this.next[node] < edges.size(); this.next[node]++) {
               ReusableStockMatcher.LongCapacityFlow.Edge edge = edges.get(this.next[node]);
               if (edge.remaining > 0L && this.level[edge.to] == this.level[node] + 1) {
                  long moved = this.send(edge.to, sink, Math.min(limit, edge.remaining));
                  if (moved > 0L) {
                     edge.remaining -= moved;
                     ReusableStockMatcher.LongCapacityFlow.Edge reverse = this.graph.get(edge.to).get(edge.reverseIndex);
                     reverse.remaining = saturatingAdd(reverse.remaining, moved);
                     return moved;
                  }
               }
            }

            return 0L;
         }
      }

      private static long saturatingAdd(long left, long right) {
         return left >= Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
      }

      private static final class Edge {
         private final int to;
         private final int reverseIndex;
         private long remaining;

         private Edge(int to, int reverseIndex, long remaining) {
            this.to = to;
            this.reverseIndex = reverseIndex;
            this.remaining = remaining;
         }
      }
   }

   public static record Result<K>(boolean feasible, Map<ReusableStockAllocationKey<K>, Long> allocation) {
      public Result(boolean feasible, Map<ReusableStockAllocationKey<K>, Long> allocation) {
         allocation = Map.copyOf(allocation);
         this.feasible = feasible;
         this.allocation = allocation;
      }
   }
}
