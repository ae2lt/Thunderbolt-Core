package com.moakiee.thunderbolt.ae2.overload.cpu;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;

public final class InsertContext {
   private final AEKey key;
   private final long requestedAmount;
   private final Actionable type;
   private long strictMatched;

   public InsertContext(AEKey key, long requestedAmount, Actionable type) {
      this.key = key;
      this.requestedAmount = requestedAmount;
      this.type = type;
   }

   public AEKey getKey() {
      return this.key;
   }

   public long getRequestedAmount() {
      return this.requestedAmount;
   }

   public Actionable getType() {
      return this.type;
   }

   public long getStrictMatched() {
      return this.strictMatched;
   }

   public void setStrictMatched(long strictMatched) {
      this.strictMatched = strictMatched;
   }
}
