package com.moakiee.thunderbolt.core.crafting.pattern;

/** Core metadata for pattern slots whose accepted candidates exceed their encoded templates. */
public interface FuzzyPatternInputs {

    boolean acceptsSameIdVariants(int slot);

    /**
     * Whether the output slot is late-bound to any key with the same primary identity.
     * Such an output may satisfy another same-id slot, but never a strict exact-key demand.
     */
    default boolean producesSameIdVariants(int slot) {
        return false;
    }
}
