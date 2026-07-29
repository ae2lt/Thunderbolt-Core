package com.moakiee.thunderbolt.ae2.timewheel;

/**
 * Public read-only view used by the optional ExtendedAE Plus suppression mixin.
 */
public final class ExtendedAePlusVirtualCraftingContext {
    private ExtendedAePlusVirtualCraftingContext() {
    }

    public static boolean isTimeWheelProviderPushActive() {
        return ExtendedAePlusVirtualCraftingCompat.isTimeWheelProviderPushActive();
    }
}
