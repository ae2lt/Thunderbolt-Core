package com.moakiee.thunderbolt.ae2.timewheel;

final class OverloadInsertAccounting {
   private OverloadInsertAccounting() {
   }

   static long strictPrefixBeforeExactOverload(long offered, long nativeExactWaiting, long exactOverloadWaiting) {
      long strict = Math.max(0L, Math.max(0L, nativeExactWaiting) - Math.max(0L, exactOverloadWaiting));
      return Math.min(Math.max(0L, offered), strict);
   }

   static long strictProbeAmount(long remaining, long simulatedExactOverload) {
      long normalizedRemaining = Math.max(0L, remaining);
      long normalizedExact = Math.max(0L, simulatedExactOverload);
      return normalizedRemaining > Long.MAX_VALUE - normalizedExact ? Long.MAX_VALUE : normalizedRemaining + normalizedExact;
   }

   static long strictMatchAfterExactOverload(long remaining, long rawStrictMatch, long simulatedExactOverload) {
      long withoutOverlap = Math.max(0L, Math.max(0L, rawStrictMatch) - Math.max(0L, simulatedExactOverload));
      return Math.min(Math.max(0L, remaining), withoutOverlap);
   }

   static boolean mayClaimOverloadRemainder(long simulatedExactOverload) {
      return simulatedExactOverload <= 0L;
   }
}
