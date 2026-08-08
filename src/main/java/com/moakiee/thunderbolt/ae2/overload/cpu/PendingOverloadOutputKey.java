package com.moakiee.thunderbolt.ae2.overload.cpu;

import java.util.Objects;
import java.util.UUID;

public record PendingOverloadOutputKey(UUID craftingId, String patternIdentity, int outputSlotIndex) {
   public PendingOverloadOutputKey(UUID craftingId, String patternIdentity, int outputSlotIndex) {
      Objects.requireNonNull(craftingId, "craftingId");
      Objects.requireNonNull(patternIdentity, "patternIdentity");
      if (patternIdentity.isBlank()) {
         throw new IllegalArgumentException("patternIdentity must not be blank");
      } else if (outputSlotIndex < 0) {
         throw new IllegalArgumentException("outputSlotIndex must be >= 0");
      } else {
         this.craftingId = craftingId;
         this.patternIdentity = patternIdentity;
         this.outputSlotIndex = outputSlotIndex;
      }
   }
}
