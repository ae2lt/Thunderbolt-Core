package com.moakiee.thunderbolt.core.crafting.batch;

import appeng.api.crafting.IPatternDetails;

public interface BatchTaskHandle {
    IPatternDetails details();

    long getValue();

    void setValue(long value);
}
