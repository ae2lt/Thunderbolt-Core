package com.moakiee.thunderbolt.core.crafting.engine;

import com.moakiee.thunderbolt.api.crafting.engine.CraftingEngineRegistry;
import com.moakiee.thunderbolt.api.crafting.engine.CraftingEngineSelection;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class CraftingEngineConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.ConfigValue<String> ENGINE = BUILDER
            .comment(
                    "Machine-default AE2 crafting-calculation engine. Used by machines/automation;",
                    "each player can pick their own engine in the terminal GUI (persisted per-player).",
                    "  none        = original AE2 calculation (default)",
                    "  thunderbolt = Thunderbolt's own linear-time fast planner",
                    "  <other id>  = a registered third-party engine (e.g. vm, eco)",
                    "Admin switch with: /thunderbolt engine <id|list> (machine default)")
            .define("craftingEngine", CraftingEngineRegistry.NONE);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private CraftingEngineConfig() {
    }

    /** Registers the server config and seeds the persisted value into the in-memory selection. */
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SPEC);
    }

    /** Loads the persisted value into {@link CraftingEngineSelection} (call when the server starts). */
    public static void seedFromConfig() {
        CraftingEngineSelection.seed(ENGINE.get());
    }

    /** Applies a new selection and persists it. Returns {@code true} if accepted. */
    public static boolean set(String id) {
        if (!CraftingEngineSelection.select(id)) {
            return false;
        }
        ENGINE.set(CraftingEngineSelection.current());
        SPEC.save();
        return true;
    }

    public static String current() {
        return CraftingEngineSelection.current();
    }
}
