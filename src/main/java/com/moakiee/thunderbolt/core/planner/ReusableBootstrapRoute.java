package com.moakiee.thunderbolt.core.planner;

import java.util.Objects;

public record ReusableBootstrapRoute<K>(Object ownerRoutingScope, K returnedSeedKey) {
   public ReusableBootstrapRoute(Object ownerRoutingScope, K returnedSeedKey) {
      Objects.requireNonNull(ownerRoutingScope, "ownerRoutingScope");
      Objects.requireNonNull(returnedSeedKey, "returnedSeedKey");
      this.ownerRoutingScope = ownerRoutingScope;
      this.returnedSeedKey = returnedSeedKey;
   }
}
