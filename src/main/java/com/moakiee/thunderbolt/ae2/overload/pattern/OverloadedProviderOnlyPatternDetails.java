package com.moakiee.thunderbolt.ae2.overload.pattern;

/**
 * Marker for pattern definitions that rely on the Overloaded Pattern Provider
 * execution semantics and must not be treated as generic AE2 patterns.
 */
public interface OverloadedProviderOnlyPatternDetails {
    PatternExecutionHostKind requiredHostKind();

    /**
     * Stable identity of this overload pattern within one crafting job.
     * <p>
     * A future IPatternDetails wrapper can expose a more precise fingerprint.
     */
    String overloadPatternIdentity();

    /**
     * Returns the overload-aware definition view that provider and CPU side code
     * can inspect without relying on AE2 global semantics.
     */
    OverloadPatternDetails overloadPatternDetailsView();

    /** True when at least one input slot accepts same-id variants while ignoring components. */
    default boolean hasFuzzyInputs() {
        var view = overloadPatternDetailsView();
        return view != null && view.inputs().stream().anyMatch(input -> input.matchMode().ignoresComponents());
    }

    default boolean isFuzzyInput(int slot) {
        var view = overloadPatternDetailsView();
        return view != null && view.inputMode(slot).ignoresComponents();
    }

    default boolean isFuzzyOutput(int slot) {
        var view = overloadPatternDetailsView();
        return view != null && view.outputMode(slot).ignoresComponents();
    }
}
