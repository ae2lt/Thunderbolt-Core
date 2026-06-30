package com.moakiee.thunderbolt.ae2.overload.pattern;

import java.util.Objects;

import com.moakiee.thunderbolt.ae2.overload.model.EncodedOverloadPattern;

/**
 * Restored editing state for an existing overload pattern item.
 */
public record EditableOverloadPatternState(
        ParsedPatternDefinition parsedPattern,
        EncodedOverloadPattern encodedPattern
) {
    public EditableOverloadPatternState {
        Objects.requireNonNull(parsedPattern, "parsedPattern");
        Objects.requireNonNull(encodedPattern, "encodedPattern");
    }
}
