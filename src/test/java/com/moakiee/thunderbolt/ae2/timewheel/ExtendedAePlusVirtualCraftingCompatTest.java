package com.moakiee.thunderbolt.ae2.timewheel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExtendedAePlusVirtualCraftingCompatTest {
    @Test
    void detectsCurrentExtendedAePlusBridgeShape() {
        assertTrue(ExtendedAePlusVirtualCraftingCompat.isVirtualCraftingEnabled(
                new EnabledProvider()));
        assertFalse(ExtendedAePlusVirtualCraftingCompat.isVirtualCraftingEnabled(
                new DisabledProvider()));
    }

    @Test
    void missingInvalidOrThrowingBridgeFailsClosed() {
        assertFalse(ExtendedAePlusVirtualCraftingCompat.isVirtualCraftingEnabled(
                new NoBridgeProvider()));
        assertFalse(ExtendedAePlusVirtualCraftingCompat.isVirtualCraftingEnabled(
                new InvalidBridgeProvider()));
        assertFalse(ExtendedAePlusVirtualCraftingCompat.isVirtualCraftingEnabled(
                new ThrowingBridgeProvider()));
        assertFalse(ExtendedAePlusVirtualCraftingCompat.isVirtualCraftingEnabled(null));
    }

    @Test
    void providerPushScopeIsNestedAndAlwaysRestorable() {
        assertFalse(ExtendedAePlusVirtualCraftingCompat.isTimeWheelProviderPushActive());
        try (var outer = ExtendedAePlusVirtualCraftingCompat.enterTimeWheelProviderPush()) {
            assertTrue(ExtendedAePlusVirtualCraftingCompat.isTimeWheelProviderPushActive());
            try (var inner = ExtendedAePlusVirtualCraftingCompat.enterTimeWheelProviderPush()) {
                assertTrue(ExtendedAePlusVirtualCraftingCompat.isTimeWheelProviderPushActive());
            }
            assertTrue(ExtendedAePlusVirtualCraftingCompat.isTimeWheelProviderPushActive());
        }
        assertFalse(ExtendedAePlusVirtualCraftingCompat.isTimeWheelProviderPushActive());
    }

    @Test
    void completionRequiresTheFinalOrdinaryTaskOnTheSameLiveJob() {
        assertTrue(ExtendedAePlusVirtualCraftingCompat.shouldRequestCompletion(
                true, true, false, false, true));
        assertFalse(ExtendedAePlusVirtualCraftingCompat.shouldRequestCompletion(
                false, true, false, false, true));
        assertFalse(ExtendedAePlusVirtualCraftingCompat.shouldRequestCompletion(
                true, false, false, false, true));
        assertFalse(ExtendedAePlusVirtualCraftingCompat.shouldRequestCompletion(
                true, true, true, false, true));
        assertFalse(ExtendedAePlusVirtualCraftingCompat.shouldRequestCompletion(
                true, true, false, true, true));
        assertFalse(ExtendedAePlusVirtualCraftingCompat.shouldRequestCompletion(
                true, true, false, false, false));
    }

    public static final class EnabledProvider {
        public boolean eap$compatIsVirtualCraftingEnabled() {
            return true;
        }
    }

    public static final class DisabledProvider {
        public boolean eap$compatIsVirtualCraftingEnabled() {
            return false;
        }
    }

    public static final class NoBridgeProvider {
    }

    public static final class InvalidBridgeProvider {
        public String eap$compatIsVirtualCraftingEnabled() {
            return "true";
        }
    }

    public static final class ThrowingBridgeProvider {
        public boolean eap$compatIsVirtualCraftingEnabled() {
            throw new IllegalStateException("broken optional bridge");
        }
    }
}
