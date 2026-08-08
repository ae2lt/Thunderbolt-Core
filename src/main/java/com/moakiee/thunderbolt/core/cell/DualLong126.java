package com.moakiee.thunderbolt.core.cell;

public final class DualLong126 {
   private DualLong126() {
   }

   public static boolean geq(long hi, long lo, long amount) {
      return hi > 0L || lo >= amount;
   }

   public static long cap(long hi, long lo) {
      return hi > 0L ? Long.MAX_VALUE : lo;
   }

   public static long mod126(long hi, long lo, int d) {
      if (d == 1) {
         return 0L;
      } else {
         long pow2_63_mod_d = (Long.MAX_VALUE % (long)d + 1L) % (long)d;
         return (hi % (long)d * pow2_63_mod_d % (long)d + lo % (long)d) % (long)d;
      }
   }

   public static void ceilDiv126(long hi, long lo, int d, long[] out) {
      if (d == 1) {
         out[0] = hi;
         out[1] = lo;
      } else if (hi == 0L) {
         out[0] = 0L;
         long q = lo / (long)d;
         out[1] = lo % (long)d == 0L ? q : q + 1L;
      } else {
         long mask32 = 4294967295L;
         long c3 = hi >>> 33;
         long c2 = hi >>> 1 & mask32;
         long c1 = (hi & 1L) << 31 | lo >>> 32;
         long c0 = lo & mask32;
         long q3 = c3 / (long)d;
         long rem = c3 % (long)d;
         rem = rem << 32 | c2;
         long q2 = rem / (long)d;
         rem %= (long)d;
         rem = rem << 32 | c1;
         long q1 = rem / (long)d;
         rem %= (long)d;
         rem = rem << 32 | c0;
         long q0 = rem / (long)d;
         rem %= (long)d;
         long low64 = q1 << 32 | q0;
         long high62 = q3 << 32 | q2;
         out[1] = low64 & Long.MAX_VALUE;
         out[0] = high62 << 1 | low64 >>> 63;
         if (rem > 0L) {
            if (out[1] == Long.MAX_VALUE) {
               out[1] = 0L;
               out[0]++;
            } else {
               out[1]++;
            }
         }
      }
   }
}
