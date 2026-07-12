package com.moakiee.thunderbolt.ae2.timewheel;

import com.moakiee.thunderbolt.CoreConfig;

public final class TimeWheelFastPlanningGate {
    private TimeWheelFastPlanningGate() {
    }

    public static boolean shouldEnableFastPlanning(Iterable<? extends CpuState> cpus) {
        if (!CoreConfig.FAST_PATH_ENABLED) {
            return false;
        }
        for (var cpu : cpus) {
            if (cpu.isActive() && cpu.isFastPlanningEnabled()) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    public interface CpuState {
        boolean isActive();

        default boolean isFastPlanningEnabled() {
            return true;
        }
    }
}
