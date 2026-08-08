package com.moakiee.thunderbolt.ae2.overload.cpu;

import com.moakiee.thunderbolt.ae2.overload.pattern.SourcePatternSnapshot;
import java.util.Objects;

public record OverloadPatternReference(String patternIdentity, SourcePatternSnapshot sourcePattern) {
   public OverloadPatternReference(String patternIdentity, SourcePatternSnapshot sourcePattern) {
      Objects.requireNonNull(patternIdentity, "patternIdentity");
      if (patternIdentity.isBlank()) {
         throw new IllegalArgumentException("patternIdentity must not be blank");
      } else {
         Objects.requireNonNull(sourcePattern, "sourcePattern");
         this.patternIdentity = patternIdentity;
         this.sourcePattern = sourcePattern;
      }
   }
}
