package com.moakiee.thunderbolt.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** Common configuration for optional Thunderbolt runtime components. */
public final class ThunderboltCommonConfig {
    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.BooleanValue ENABLE_CP_SAT_PLANNER;

    static {
        var builder = new ForgeConfigSpec.Builder();
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
