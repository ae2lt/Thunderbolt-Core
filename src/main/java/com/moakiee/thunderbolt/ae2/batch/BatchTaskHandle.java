package com.moakiee.thunderbolt.ae2.batch;

import appeng.api.crafting.IPatternDetails;

public interface BatchTaskHandle {
    IPatternDetails details();

    long getValue();

    void setValue(long value);
}
