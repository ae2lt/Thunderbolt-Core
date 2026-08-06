package com.moakiee.thunderbolt.api.crafting.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 当前选择的合成计算引擎（互斥，至多一个）。
 *
 * <p>默认 {@link CraftingEngineRegistry#NONE}（原版路径）。玩家通过游戏内命令
 * {@code /thunderbolt engine <id|list>} 切换；选择持久化在服务端配置中并自动同步到客户端。
 * 第三方引擎被选中时，闪电自身的深层规划器让位（不与其抢计算）。
 */
public final class CraftingEngineSelection {

    private static volatile String current = CraftingEngineRegistry.NONE;

    private CraftingEngineSelection() {
    }

    public static String current() {
        return current;
    }

    /**
     * Loads a persisted value into the in-memory selection without availability validation.
     * Used when the server config is (re)loaded; an engine that is no longer installed simply
     * won't be routable and the mixin falls through safely.
     */
    public static void seed(String id) {
        if (id != null && !id.isBlank()) {
            current = id;
        }
    }

    /**
     * Sets the selection to a registered and available engine id. Invalid ids are rejected and the
     * previous selection is kept. Pass {@link CraftingEngineRegistry#NONE} to return to vanilla.
     */
    public static boolean select(String id) {
        if (CraftingEngineRegistry.NONE.equals(id)) {
            current = CraftingEngineRegistry.NONE;
            return true;
        }
        if (CraftingEngineRegistry.isAvailable(id)) {
            current = id;
            return true;
        }
        return false;
    }

    /** All ids the player may choose from right now: none + every available engine. */
    public static List<String> availableIds() {
        var ids = new ArrayList<String>();
        ids.add(CraftingEngineRegistry.NONE);
        for (var engine : CraftingEngineRegistry.available()) {
            ids.add(engine.id());
        }
        return List.copyOf(ids);
    }

    /**
     * Whether Thunderbolt's own deep planner should drive native calculations right now. True when
     * nothing is selected (Thunderbolt's default behavior) or when Thunderbolt itself is selected;
     * false when a third-party engine owns the calculation.
     */
    public static boolean usesThunderboltPlanner() {
        String id = current;
        return CraftingEngineRegistry.NONE.equals(id) || CraftingEngineRegistry.THUNDERBOLT.equals(id);
    }
}
