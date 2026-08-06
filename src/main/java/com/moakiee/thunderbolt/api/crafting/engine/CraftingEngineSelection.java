package com.moakiee.thunderbolt.api.crafting.engine;

import java.util.ArrayList;
import java.util.List;


public final class CraftingEngineSelection {

    private static volatile String current = CraftingEngineRegistry.NONE;
    private static volatile String playerCurrent = CraftingEngineRegistry.NONE;

    private CraftingEngineSelection() {
    }

    /** 机器默认引擎（配置文件值）；机器/自动化请求使用它。 */
    public static String current() {
        return current;
    }

    /** 本地玩家个人选择的引擎（客户端镜像，用于界面显示）。 */
    public static String playerCurrent() {
        return playerCurrent;
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

    /** Loads the local player's personal engine choice mirror (client side, from the sync packet). */
    public static void seedPlayer(String id) {
        if (id != null && !id.isBlank()) {
            playerCurrent = id;
        }
    }

    /**
     * Sets the machine default to a registered and available engine id. Invalid ids are rejected
     * and the previous selection is kept. Pass {@link CraftingEngineRegistry#NONE} to return to vanilla.
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
