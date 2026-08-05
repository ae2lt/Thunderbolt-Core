package com.moakiee.thunderbolt.core.crafting.support;

/** Internal metadata for pattern slots whose accepted candidates exceed their encoded templates. */
public interface FuzzyPatternInputs {

    boolean acceptsSameIdVariants(int slot);
}
