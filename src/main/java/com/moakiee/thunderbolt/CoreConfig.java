package com.moakiee.thunderbolt;

/** Lightweight host-configured values shared by Thunderbolt's low-level hooks. */
public final class CoreConfig {
    /**
     * Channel capacity granted per overloaded controller by the channel/max-flow grid mixins.
     * Defaults to 128; the host mod (AE2 Lightning Tech) overwrites it from its own config during
     * setup via {@link #setChannelsPerController(int)} so the value stays user-configurable.
     */
    private static volatile int channelsPerController = 128;

    public static int channelsPerController() {
        return channelsPerController;
    }

    public static void setChannelsPerController(int value) {
        channelsPerController = value;
    }

    private CoreConfig() {
    }
}
