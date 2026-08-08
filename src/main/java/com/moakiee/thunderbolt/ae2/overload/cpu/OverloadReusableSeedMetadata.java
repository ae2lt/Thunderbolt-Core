package com.moakiee.thunderbolt.ae2.overload.cpu;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record OverloadReusableSeedMetadata(List<OverloadConsumerCredit> consumerCredits, boolean sharedPool) {
   public OverloadReusableSeedMetadata(List<OverloadConsumerCredit> consumerCredits, boolean sharedPool) {
      consumerCredits = OverloadConsumerCredit.normalize(consumerCredits);
      if (consumerCredits.isEmpty()) {
         throw new IllegalArgumentException("consumerCredits must not be empty");
      } else {
         this.consumerCredits = consumerCredits;
         this.sharedPool = sharedPool;
      }
   }

   public OverloadReusableSeedMetadata(List<OverloadConsumerCredit> consumerCredits) {
      this(consumerCredits, false);
   }

   public OverloadReusableSeedMetadata(Map<UUID, Long> consumerCredits) {
      this(OverloadConsumerCredit.fromAmounts(consumerCredits), false);
   }

   public OverloadReusableSeedMetadata(UUID groupId, boolean sharedPool, long amount) {
      this(List.of(new OverloadConsumerCredit(groupId, amount)), sharedPool);
   }

   public long amount() {
      return OverloadConsumerCredit.total(this.consumerCredits);
   }

   public UUID groupId() {
      return this.consumerCredits.size() == 1 ? this.consumerCredits.get(0).consumerId() : null;
   }
}
