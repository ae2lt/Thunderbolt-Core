package com.moakiee.thunderbolt;

/**
 * Lightweight feature switches. The fast path is a behavior-preserving optimization that falls back
 * to AE2 whenever it is out of scope, but a global kill switch is kept for safe rollout / A-B
 * comparison against vanilla AE2.
 */
public final class CoreConfig {

    /** System property: {@code -Dthunderbolt.fastPath=false} disables the fast-path planner. */
    public static final boolean FAST_PATH_ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("thunderbolt.fastPath", "true"));

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
