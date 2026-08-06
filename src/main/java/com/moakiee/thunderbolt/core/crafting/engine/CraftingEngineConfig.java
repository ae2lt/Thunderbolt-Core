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
                    "全局机器 AE2 合成计算引擎（机器/自动化默认路由）：",
                    "  none        = 原版 AE2 计算（默认）",
                    "  thunderbolt = 闪电线性快速规划",
                    "  <other id>  = 已注册的第三方引擎（如 vm、eco）",
                    "管理员命令: /thunderbolt engine <id|list>；玩家个人引擎用终端齿轮按钮")
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
