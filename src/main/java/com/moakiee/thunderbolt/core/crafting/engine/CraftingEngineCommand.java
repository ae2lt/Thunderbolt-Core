package com.moakiee.thunderbolt.core.crafting.engine;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import com.moakiee.thunderbolt.api.crafting.engine.CraftingEngineSelection;
import com.moakiee.thunderbolt.core.crafting.engine.net.CraftingEngineNetwork;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class CraftingEngineCommand {

    private CraftingEngineCommand() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("thunderbolt")
                .then(Commands.literal("engine")
                        .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                        .then(Commands.literal("me")
                                .then(Commands.argument("engine", StringArgumentType.word())
                                        .executes(ctx -> selectMe(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "engine")))))
                        .then(Commands.argument("engine", StringArgumentType.word())
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> select(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "engine"))))));
    }

    private static int list(CommandSourceStack source) {
        String playerPart = "";
        if (source.getEntity() instanceof ServerPlayer player) {
            String personal = PlayerEngineSelection.get(player);
            playerPart = " | 我的引擎: " + (personal == null ? "默认（随机器）" : personal);
        }
        String msg = "AE2 合成计算引擎 — 全局机器: " + CraftingEngineSelection.current()
                + playerPart
                + " | 可选: " + String.join(", ", CraftingEngineSelection.availableIds());
        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    /** 设置执行命令的玩家自己的引擎（持久化在玩家 NBT）。 */
    private static int selectMe(CommandSourceStack source, String id) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("该命令只能由玩家在游戏内执行"));
            return 0;
        }
        if (CraftingEngineNetwork.applyPlayerSelection(player, id)) {
            source.sendSuccess(() -> Component.literal("你的合成计算引擎已切换为: " + id), true);
            return 1;
        }
        source.sendFailure(Component.literal(
                "未知或不可用的引擎: " + id + "（可用: " + String.join(", ", CraftingEngineSelection.availableIds()) + "）"));
        return 0;
    }

    /** 设置机器默认引擎（需要权限 2），持久化在配置文件中。 */
    private static int select(CommandSourceStack source, String id) {
        if (CraftingEngineNetwork.applySelection(id)) {
            source.sendSuccess(
                    () -> Component.literal("全局机器引擎已切换为: " + CraftingEngineSelection.current()),
                    true);
            return 1;
        }
        source.sendFailure(Component.literal(
                "未知或不可用的引擎: " + id + "（可用: " + String.join(", ", CraftingEngineSelection.availableIds()) + "）"));
        return 0;
    }
}
