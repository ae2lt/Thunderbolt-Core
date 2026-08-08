package com.moakiee.thunderbolt.core.planner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ObjLongConsumer;

public final class DurabilityChain<K> {
   private final List<K> links;
   private final long n;
   private final long[] stockPerLink;
   private final long totalUses;

   private DurabilityChain(List<K> links, long[] stockPerLink, long totalUses) {
      this.links = List.copyOf(links);
      this.n = (long)links.size();
      this.stockPerLink = stockPerLink;
      this.totalUses = totalUses;
   }

   public List<K> links() {
      return this.links;
   }

   public long n() {
      return this.n;
   }

   public long totalUses() {
      return this.totalUses;
   }

   public K carrier() {
      return this.links.get(0);
   }

   public static <K> DurabilityChain<K> build(K full, Function<K, K> remaining, Function<K, Long> stock, long maxSteps) {
      if (full != null && remaining.apply(full) != null) {
         List<K> links = new ArrayList<>();
         Set<K> guard = new HashSet<>();
         K cur = full;

         while (cur != null && guard.add(cur)) {
            links.add(cur);
            cur = remaining.apply(cur);
            if ((long)links.size() > maxSteps) {
               return null;
            }
         }

         if (links.size() < 2) {
            return null;
         } else {
            long n = (long)links.size();
            long[] stockPerLink = new long[(int)n];
            long totalUses = 0L;

            for (int i = 0; (long)i < n; i++) {
               long cnt = Math.max(0L, stock.apply(links.get(i)));
               stockPerLink[i] = cnt;
               totalUses = Sat.add(totalUses, Sat.mul(cnt, n - (long)i));
            }

            return new DurabilityChain<>(links, stockPerLink, totalUses);
         }
      } else {
         return null;
      }
   }

   public void chargeFromStock(long uses, ObjLongConsumer<K> sink) {
      long remaining = uses;

      for (int i = this.links.size() - 1; i >= 0 && remaining > 0L; i--) {
         long perTool = this.n - (long)i;
         long have = this.stockPerLink[i];
         if (have > 0L && perTool > 0L) {
            long toolsNeeded = Math.min(have, Sat.ceilDiv(remaining, perTool));
            sink.accept(this.links.get(i), toolsNeeded);
            remaining -= Sat.mul(toolsNeeded, perTool);
         }
      }
   }
}
