package com.moakiee.thunderbolt.core.planner;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

final class CycleAnalysis<K> {
   private static final int MAX_WEIGHT_BITS = 128;
   private final Map<K, CycleAnalysis.Kind> kindByMember;
   private final Set<K> directlyReorientable;
   private final Map<K, Set<K>> membersByMember;

   private CycleAnalysis(Map<K, CycleAnalysis.Kind> kindByMember, Set<K> directlyReorientable, Map<K, Set<K>> membersByMember) {
      this.kindByMember = Map.copyOf(kindByMember);
      this.directlyReorientable = Set.copyOf(directlyReorientable);
      this.membersByMember = Map.copyOf(membersByMember);
   }

   static <K> CycleAnalysis<K> analyze(CraftGraph<K> graph, K target) {
      Map<K, List<K>> adjacency = reachableAdjacency(graph, target);
      Map<K, List<K>> reverse = reverse(adjacency);
      List<K> finishOrder = finishOrder(adjacency);
      Map<K, CycleAnalysis.Kind> kinds = new HashMap<>();
      Map<K, Set<K>> membersBySccMember = new HashMap<>();
      Set<K> assigned = new HashSet<>();

      for (int i = finishOrder.size() - 1; i >= 0; i--) {
         K root = finishOrder.get(i);
         if (assigned.add(root)) {
            Set<K> members = new LinkedHashSet<>();
            Deque<K> stack = new ArrayDeque<>();
            stack.push(root);

            while (!stack.isEmpty()) {
               K node = stack.pop();
               members.add(node);

               for (K previous : reverse.getOrDefault(node, List.of())) {
                  if (assigned.add(previous)) {
                     stack.push(previous);
                  }
               }
            }

            boolean selfLoop = members.size() == 1 && adjacency.getOrDefault(root, List.of()).contains(root);
            CycleAnalysis.Kind kind = members.size() <= 1 && !selfLoop ? CycleAnalysis.Kind.ACYCLIC : classify(graph, members);
            if (members.size() > 1 || selfLoop) {
               Set<K> frozen = Set.copyOf(members);

               for (K member : members) {
                  membersBySccMember.put(member, frozen);
               }
            }

            for (K member : members) {
               kinds.put(member, kind);
            }
         }
      }

      return new CycleAnalysis<>(kinds, directlyReorientable(graph, adjacency.keySet()), membersBySccMember);
   }

   CycleAnalysis.Kind kindOf(K key) {
      return this.kindByMember.getOrDefault(key, CycleAnalysis.Kind.ACYCLIC);
   }

   Set<K> membersOf(K key) {
      return this.membersByMember.getOrDefault(key, Set.of());
   }

   boolean mayReorient(K key) {
      return this.kindOf(key).mayReorient() || this.directlyReorientable.contains(key);
   }

   private static <K> Set<K> directlyReorientable(CraftGraph<K> graph, Set<K> reachable) {
      Map<K, Map<K, Set<CycleAnalysis.Ratio>>> ratios = new HashMap<>();

      for (K output : reachable) {
         for (CraftPattern<K> pattern : graph.patternsFor(output)) {
            for (CraftInput<K> input : pattern.inputs()) {
               if (!input.returned() && input.remainder() == null && !hasMaterialByproduct(pattern, output, input.key())) {
                  long gcd = gcd(pattern.outputAmount(), input.amount());
                  CycleAnalysis.Ratio ratio = new CycleAnalysis.Ratio(pattern.outputAmount() / gcd, input.amount() / gcd);
                  ratios.computeIfAbsent(output, ignored -> new HashMap<>()).computeIfAbsent(input.key(), ignored -> new HashSet<>()).add(ratio);
               }
            }
         }
      }

      Set<K> result = new HashSet<>();

      for (Entry<K, Map<K, Set<CycleAnalysis.Ratio>>> fromEntry : ratios.entrySet()) {
         K from = fromEntry.getKey();

         for (Entry<K, Set<CycleAnalysis.Ratio>> toEntry : fromEntry.getValue().entrySet()) {
            K to = toEntry.getKey();
            Set<CycleAnalysis.Ratio> reverse = ratios.getOrDefault(to, Map.of()).get(from);
            if (reverse != null) {
               for (CycleAnalysis.Ratio ratio : toEntry.getValue()) {
                  if (reverse.contains(ratio.reciprocal())) {
                     result.add(from);
                     result.add(to);
                     break;
                  }
               }
            }
         }
      }

      return result;
   }

   private static <K> boolean hasMaterialByproduct(CraftPattern<K> pattern, K output, K input) {
      for (CraftOutput<K> byproduct : pattern.byproducts()) {
         if (output.equals(byproduct.key()) || input.equals(byproduct.key())) {
            return true;
         }
      }

      return false;
   }

   private static long gcd(long a, long b) {
      while (b != 0L) {
         long next = a % b;
         a = b;
         b = next;
      }

      return a;
   }

   private static <K> Map<K, List<K>> reachableAdjacency(CraftGraph<K> graph, K target) {
      Map<K, List<K>> adjacency = new LinkedHashMap<>();
      Set<K> seen = new LinkedHashSet<>();
      Deque<K> queue = new ArrayDeque<>();
      seen.add(target);
      queue.add(target);

      while (!queue.isEmpty()) {
         K output = queue.remove();
         List<K> children = new ArrayList<>();

         for (CraftPattern<K> pattern : graph.patternsFor(output)) {
            for (CraftInput<K> input : pattern.inputs()) {
               children.add(input.key());
               if (seen.add(input.key())) {
                  queue.add(input.key());
               }
            }
         }

         adjacency.put(output, List.copyOf(children));
      }

      for (K node : seen) {
         adjacency.putIfAbsent(node, List.of());
      }

      return adjacency;
   }

   private static <K> Map<K, List<K>> reverse(Map<K, List<K>> adjacency) {
      Map<K, List<K>> reverse = new LinkedHashMap<>();

      for (K node : adjacency.keySet()) {
         reverse.put(node, new ArrayList<>());
      }

      for (Entry<K, List<K>> entry : adjacency.entrySet()) {
         for (K child : entry.getValue()) {
            reverse.computeIfAbsent(child, ignored -> new ArrayList<>()).add(entry.getKey());
         }
      }

      return reverse;
   }

   private static <K> List<K> finishOrder(Map<K, List<K>> adjacency) {
      List<K> finish = new ArrayList<>(adjacency.size());
      Set<K> visited = new HashSet<>();

      for (K root : adjacency.keySet()) {
         if (visited.add(root)) {
            Deque<CycleAnalysis.DfsFrame<K>> stack = new ArrayDeque<>();
            stack.push(new CycleAnalysis.DfsFrame<>(root, adjacency.getOrDefault(root, List.of())));

            while (!stack.isEmpty()) {
               CycleAnalysis.DfsFrame<K> frame = stack.peek();
               if (frame.index < frame.children.size()) {
                  K child = frame.children.get(frame.index++);
                  if (visited.add(child)) {
                     stack.push(new CycleAnalysis.DfsFrame<>(child, adjacency.getOrDefault(child, List.of())));
                  }
               } else {
                  finish.add(frame.node);
                  stack.pop();
               }
            }
         }
      }

      return finish;
   }

   private static <K> CycleAnalysis.Kind classify(CraftGraph<K> graph, Set<K> members) {
      CycleAnalysis.InternalMode mode = null;
      boolean hasExternalInputs = false;
      int internalPatternCount = 0;
      Map<K, List<CycleAnalysis.WeightEdge<K>>> weights = new HashMap<>();

      for (K output : members) {
         for (CraftPattern<K> pattern : graph.patternsFor(output)) {
            List<CraftInput<K>> internal = new ArrayList<>(2);

            for (CraftInput<K> input : pattern.inputs()) {
               if (members.contains(input.key())) {
                  internal.add(input);
               } else {
                  hasExternalInputs = true;
               }
            }

            if (!internal.isEmpty()) {
               internalPatternCount++;
               if (internal.size() != 1) {
                  return CycleAnalysis.Kind.COMPLEX;
               }

               for (CraftOutput<K> byproduct : pattern.byproducts()) {
                  if (members.contains(byproduct.key())) {
                     return CycleAnalysis.Kind.COMPLEX;
                  }
               }

               CraftInput<K> inputx = internal.get(0);
               CycleAnalysis.InternalMode thisMode;
               if (inputx.returned() && inputx.uses() == Long.MAX_VALUE && inputx.remainder() == null) {
                  thisMode = CycleAnalysis.InternalMode.CATALYST;
               } else {
                  if (inputx.returned() || inputx.remainder() != null) {
                     return CycleAnalysis.Kind.COMPLEX;
                  }

                  thisMode = CycleAnalysis.InternalMode.CONVERSION;
                  weights.computeIfAbsent(output, ignored -> new ArrayList<>())
                     .add(new CycleAnalysis.WeightEdge<>(inputx.key(), pattern.outputAmount(), inputx.amount()));
                  weights.computeIfAbsent(inputx.key(), ignored -> new ArrayList<>())
                     .add(new CycleAnalysis.WeightEdge<>(output, inputx.amount(), pattern.outputAmount()));
               }

               if (mode != null && mode != thisMode) {
                  return CycleAnalysis.Kind.COMPLEX;
               }

               mode = thisMode;
            }
         }
      }

      if (internalPatternCount == 0 || mode == null) {
         return CycleAnalysis.Kind.COMPLEX;
      } else if (mode == CycleAnalysis.InternalMode.CATALYST) {
         return CycleAnalysis.Kind.CATALYST_STATE;
      } else if (!weightsAreConsistent(members, weights)) {
         return CycleAnalysis.Kind.LOSSY_CONVERSION;
      } else {
         return hasExternalInputs ? CycleAnalysis.Kind.CATALYZED_CONVERSION : CycleAnalysis.Kind.PURE_CONVERSION;
      }
   }

   private static <K> boolean weightsAreConsistent(Set<K> members, Map<K, List<CycleAnalysis.WeightEdge<K>>> edges) {
      Map<K, CycleAnalysis.Fraction> weights = new HashMap<>();
      Deque<K> queue = new ArrayDeque<>();
      K root = members.iterator().next();
      weights.put(root, CycleAnalysis.Fraction.one());
      queue.add(root);

      while (!queue.isEmpty()) {
         K from = queue.remove();
         CycleAnalysis.Fraction base = weights.get(from);

         for (CycleAnalysis.WeightEdge<K> edge : edges.getOrDefault(from, List.of())) {
            CycleAnalysis.Fraction proposed = base.multiply(edge.numerator(), edge.denominator());
            if (proposed == null) {
               return false;
            }

            CycleAnalysis.Fraction existing = weights.putIfAbsent(edge.to(), proposed);
            if (existing == null) {
               queue.add(edge.to());
            } else if (!existing.equals(proposed)) {
               return false;
            }
         }
      }

      return weights.size() == members.size();
   }

   private static final class DfsFrame<K> {
      final K node;
      final List<K> children;
      int index;

      DfsFrame(K node, List<K> children) {
         this.node = node;
         this.children = children;
      }
   }

   private static record Fraction(BigInteger numerator, BigInteger denominator) {
      static CycleAnalysis.Fraction one() {
         return new CycleAnalysis.Fraction(BigInteger.ONE, BigInteger.ONE);
      }

      CycleAnalysis.Fraction multiply(long numerator, long denominator) {
         BigInteger n = this.numerator.multiply(BigInteger.valueOf(numerator));
         BigInteger d = this.denominator.multiply(BigInteger.valueOf(denominator));
         BigInteger gcd = n.gcd(d);
         n = n.divide(gcd);
         d = d.divide(gcd);
         return n.bitLength() <= 128 && d.bitLength() <= 128 ? new CycleAnalysis.Fraction(n, d) : null;
      }
   }

   private static enum InternalMode {
      CONVERSION,
      CATALYST;
   }

   static enum Kind {
      ACYCLIC,
      PURE_CONVERSION,
      CATALYZED_CONVERSION,
      LOSSY_CONVERSION,
      CATALYST_STATE,
      COMPLEX;

      boolean mayReorient() {
         return this == PURE_CONVERSION || this == CATALYZED_CONVERSION || this == LOSSY_CONVERSION;
      }
   }

   private static record Ratio(long numerator, long denominator) {
      CycleAnalysis.Ratio reciprocal() {
         return new CycleAnalysis.Ratio(this.denominator, this.numerator);
      }
   }

   private static record WeightEdge<K>(K to, long numerator, long denominator) {
   }
}
