package com.moakiee.thunderbolt.ae2.overload.pattern;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

/** Shared containment rules for connecting a producer output to an overload input. */
public final class OverloadPatternMatchPolicy {
    private OverloadPatternMatchPolicy() {
    }

    /**
     * Strict outputs may feed exact inputs and ID_ONLY inputs. A late-bound ID_ONLY output may only
     * feed an ID_ONLY input, because its runtime component state is not known during planning.
     */
    public static boolean accepts(
            GenericStack[] possibleInputs,
            boolean inputIdOnly,
            AEKey output,
            boolean outputIdOnly) {
        if (possibleInputs == null || output == null || outputIdOnly && !inputIdOnly) {
            return false;
        }
        for (var possible : possibleInputs) {
            if (possible == null || possible.what() == null) {
                continue;
            }
            if (inputIdOnly
                    ? samePrimaryIdentity(possible.what(), output)
                    : possible.what().equals(output)) {
                return true;
            }
        }
        return false;
    }

    public static boolean samePrimaryIdentity(AEKey left, AEKey right) {
        return left != null && right != null
                && left.dropSecondary().equals(right.dropSecondary());
    }
}
