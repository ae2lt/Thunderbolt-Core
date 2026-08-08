package com.moakiee.thunderbolt.ae2.overload.pattern;

public interface OverloadedProviderOnlyPatternDetails {
   PatternExecutionHostKind requiredHostKind();

   String overloadPatternIdentity();

   OverloadPatternDetails overloadPatternDetailsView();

   default boolean hasFuzzyInputs() {
      OverloadPatternDetails view = this.overloadPatternDetailsView();
      return view != null && view.inputs().stream().anyMatch(input -> input.matchMode().ignoresComponents());
   }

   default boolean isFuzzyInput(int slot) {
      OverloadPatternDetails view = this.overloadPatternDetailsView();
      return view != null && view.inputMode(slot).ignoresComponents();
   }

   default boolean isFuzzyOutput(int slot) {
      OverloadPatternDetails view = this.overloadPatternDetailsView();
      return view != null && view.outputMode(slot).ignoresComponents();
   }
}
