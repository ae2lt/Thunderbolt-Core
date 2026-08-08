package com.moakiee.thunderbolt.core.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

public final class BoundedCombinations {
   private BoundedCombinations() {
   }

   public static <T> List<List<T>> bestFirst(List<List<T>> slots, int limit) {
      List<List<T>> out = new ArrayList<>();
      if (limit <= 0) {
         return out;
      } else {
         int n = slots.size();

         for (List<T> slot : slots) {
            if (slot.isEmpty()) {
               return out;
            }
         }

         int[] start = new int[n + 1];
         PriorityQueue<int[]> heap = new PriorityQueue<>(Comparator.comparingInt(a -> a[n]));
         Set<String> visited = new HashSet<>();
         heap.add(start);
         visited.add(key(start, n));

         while (!heap.isEmpty() && out.size() < limit) {
            int[] cur = heap.poll();
            List<T> combo = new ArrayList<>(n);

            for (int s = 0; s < n; s++) {
               combo.add(slots.get(s).get(cur[s]));
            }

            out.add(combo);

            for (int s = 0; s < n; s++) {
               if (cur[s] + 1 < slots.get(s).size()) {
                  int[] next = (int[])cur.clone();
                  next[s]++;
                  next[n]++;
                  String id = key(next, n);
                  if (visited.add(id)) {
                     heap.add(next);
                  }
               }
            }
         }

         return out;
      }
   }

   private static String key(int[] idx, int n) {
      StringBuilder sb = new StringBuilder(n * 3);

      for (int i = 0; i < n; i++) {
         sb.append(idx[i]).append(',');
      }

      return sb.toString();
   }
}
