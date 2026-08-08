package com.moakiee.thunderbolt.core.planner;

public final class Sat {
   public static final long SAT = 2305843009213693951L;

   private Sat() {
   }

   public static boolean isSaturated(long value) {
      return value >= 2305843009213693951L;
   }

   public static long add(long a, long b) {
      long r = a + b;
      return r < 2305843009213693951L && r >= 0L ? r : 2305843009213693951L;
   }

   public static long mul(long a, long b) {
      if (a == 0L || b == 0L) {
         return 0L;
      } else if (a < 2305843009213693951L && b < 2305843009213693951L && a <= 2305843009213693951L / b) {
         long r = a * b;
         return r < 2305843009213693951L && r >= 0L ? r : 2305843009213693951L;
      } else {
         return 2305843009213693951L;
      }
   }

   public static long ceilDiv(long value, long div) {
      if (value >= 2305843009213693951L) {
         return 2305843009213693951L;
      } else {
         return value == 0L ? 0L : (value - 1L) / div + 1L;
      }
   }
}
