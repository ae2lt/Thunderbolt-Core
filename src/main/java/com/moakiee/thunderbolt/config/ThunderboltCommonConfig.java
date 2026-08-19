package com.moakiee.thunderbolt.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Common configuration for optional Thunderbolt runtime components. */
public final class ThunderboltCommonConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue ENABLE_CP_SAT_PLANNER;

    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("planning");
        ENABLE_CP_SAT_PLANNER = builder
                .comment(
                        "Enable the experimental OR-Tools CP-SAT crafting planner.",
                        "When enabled, Thunderbolt downloads and verifies the matching native runtime",
                        "during startup. A download or load failure only disables this planner for that run.")
                .define("enableCpSatPlanner", false);
        builder.pop();
        SPEC = builder.build();
    }

    private ThunderboltCommonConfig() {
    }

    public static boolean enableCpSatPlanner() {
        return ENABLE_CP_SAT_PLANNER.get();
    }
}
