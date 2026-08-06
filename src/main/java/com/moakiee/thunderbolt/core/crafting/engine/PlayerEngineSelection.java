package com.moakiee.thunderbolt.core.crafting.engine;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.me.helpers.PlayerSource;

import com.moakiee.thunderbolt.api.crafting.engine.CraftingEngineRegistry;

public final class PlayerEngineSelection {

    private static final String NBT_KEY = "thunderbolt:engine";

    private PlayerEngineSelection() {
    }

    /**
     * 该玩家的个人引擎 id；未设置返回 {@code null}（回落机器默认）。
     */
    public static String get(Player player) {
        if (player == null) {
            return null;
        }
        var nbt = player.getPersistentData();
        if (!nbt.contains(NBT_KEY)) {
            return null;
        }
        String id = nbt.getString(NBT_KEY);
        return id.isBlank() ? null : id;
    }

    /** 设置玩家个人引擎；传 {@code null} / {@code none} / 空串表示清除（回落机器默认）。 */
    public static void set(ServerPlayer player, String id) {
        var nbt = player.getPersistentData();
        if (id == null || id.isBlank() || CraftingEngineRegistry.NONE.equals(id)) {
            nbt.remove(NBT_KEY);
        } else {
            nbt.putString(NBT_KEY, id);
        }
    }

    /** 该请求是否由玩家发起（action source 是 {@link PlayerSource}）。 */
    public static boolean isPlayerRequest(ICraftingSimulationRequester requester) {
        return requester != null
                && requester.getActionSource() instanceof PlayerSource ps
                && ps.player().isPresent();
    }

    /**
     * 解析一次请求应使用的引擎：
     *
     * <ul>
     *   <li>玩家发起的请求 → 玩家个人选择，未设置时回落 {@code machineDefault}；</li>
     *   <li>机器/自动化请求 → {@code machineDefault}（配置文件内机器默认路由）。</li>
     * </ul>
     */
    public static String resolve(ICraftingSimulationRequester requester, String machineDefault) {
        if (requester != null) {
            var actionSource = requester.getActionSource();
            if (actionSource instanceof PlayerSource ps) {
                Player player = ps.player().orElse(null);
                if (player != null) {
                    String playerChoice = get(player);
                    if (playerChoice != null) {
                        return playerChoice;
                    }
                }
            }
        }
        return machineDefault;
    }
}
