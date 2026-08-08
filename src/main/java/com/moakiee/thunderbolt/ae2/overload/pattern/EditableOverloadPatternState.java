package com.moakiee.thunderbolt.ae2.overload.pattern;

import com.moakiee.thunderbolt.ae2.overload.model.EncodedOverloadPattern;
import java.util.Objects;

public record EditableOverloadPatternState(ParsedPatternDefinition parsedPattern, EncodedOverloadPattern encodedPattern) {
   public EditableOverloadPatternState(ParsedPatternDefinition parsedPattern, EncodedOverloadPattern encodedPattern) {
      Objects.requireNonNull(parsedPattern, "parsedPattern");
      Objects.requireNonNull(encodedPattern, "encodedPattern");
      this.parsedPattern = parsedPattern;
      this.encodedPattern = encodedPattern;
   }
}
