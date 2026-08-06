package com.moakiee.thunderbolt.core.crafting.engine;

// [Thunderbolt-Core] engine-selection + mixin-package-fixes changeset (PR -> refactor/thunderbolt-three-layer-clean, 2026-08-07)

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import com.moakiee.thunderbolt.api.crafting.engine.CraftingEngineSelection;
import com.moakiee.thunderbolt.core.crafting.engine.net.CraftingEngineNetwork;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
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
                        .then(Commands.argument("engine", StringArgumentType.word())
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> select(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "engine"))))));
    }

    private static int list(CommandSourceStack source) {
        String msg = "AE2 合成计算引擎 — 当前: " + CraftingEngineSelection.current()
                + " | 可选: " + String.join(", ", CraftingEngineSelection.availableIds());
        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int select(CommandSourceStack source, String id) {
        if (CraftingEngineNetwork.applySelection(id)) {
            source.sendSuccess(
                    () -> Component.literal("合成计算引擎已切换到: " + CraftingEngineSelection.current()),
                    true);
            return 1;
        }
        source.sendFailure(Component.literal(
                "未知或不可用的引擎: " + id + "（可用: " + String.join(", ", CraftingEngineSelection.availableIds()) + "）"));
        return 0;
    }
}
