package com.moakiee.thunderbolt.core.crafting.engine.net;

import java.util.Optional;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import com.moakiee.thunderbolt.api.crafting.engine.CraftingEngineRegistry;
import com.moakiee.thunderbolt.api.crafting.engine.CraftingEngineSelection;
import com.moakiee.thunderbolt.core.crafting.engine.CraftingEngineConfig;
import com.moakiee.thunderbolt.core.crafting.engine.PlayerEngineSelection;

public final class CraftingEngineNetwork {

    private CraftingEngineNetwork() {
    }

    /** Client → server: set the sender player's personal crafting engine (machine default unchanged). */
    public record SetCraftingEnginePayload(String engineId) implements CustomPacketPayload {
        public static final Type<SetCraftingEnginePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath("thunderbolt", "set_crafting_engine"));
        public static final StreamCodec<ByteBuf, SetCraftingEnginePayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, SetCraftingEnginePayload::engineId,
                SetCraftingEnginePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client → server: set the global machine default engine (op required, affects all machines). */
    public record SetGlobalEnginePayload(String engineId) implements CustomPacketPayload {
        public static final Type<SetGlobalEnginePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath("thunderbolt", "set_global_engine"));
        public static final StreamCodec<ByteBuf, SetGlobalEnginePayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, SetGlobalEnginePayload::engineId,
                SetGlobalEnginePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Server → client: the receiving player's personal engine choice (present only when they set
     * one) plus the machine default. Sent on login and after every change.
     */
    public record SyncCraftingEnginePayload(Optional<String> playerEngine, String machineDefault)
            implements CustomPacketPayload {
        public static final Type<SyncCraftingEnginePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath("thunderbolt", "sync_crafting_engine"));
        public static final StreamCodec<ByteBuf, SyncCraftingEnginePayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), SyncCraftingEnginePayload::playerEngine,
                ByteBufCodecs.STRING_UTF8, SyncCraftingEnginePayload::machineDefault,
                SyncCraftingEnginePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("thunderbolt");

        // Client → server: write the sender's personal engine choice into their NBT.
        registrar.playToServer(SetCraftingEnginePayload.TYPE, SetCraftingEnginePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    var player = context.player();
                    if (player instanceof ServerPlayer serverPlayer) {
                        applyPlayerSelection(serverPlayer, payload.engineId());
                    }
                }));

        // Client → server: set the global machine default (op required, affects all machines).
        registrar.playToServer(SetGlobalEnginePayload.TYPE, SetGlobalEnginePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    var player = context.player();
                    if (player instanceof ServerPlayer serverPlayer) {
                        applyGlobalSelection(serverPlayer, payload.engineId());
                    }
                }));

        registrar.playToClient(SyncCraftingEnginePayload.TYPE, SyncCraftingEnginePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    payload.playerEngine().ifPresent(CraftingEngineSelection::seedPlayer);
                    CraftingEngineSelection.seed(payload.machineDefault());
                }));
    }

    /** Applies a new machine default on the server (persisted to config) and broadcasts it. */
    public static boolean applySelection(String id) {
        if (!CraftingEngineConfig.set(id)) {
            return false;
        }
        syncToAll();
        return true;
    }

    /** Applies a player's personal engine choice (persisted in their NBT) and resyncs them. */
    public static boolean applyPlayerSelection(ServerPlayer player, String id) {
        if (!CraftingEngineRegistry.NONE.equals(id) && !CraftingEngineRegistry.isAvailable(id)) {
            return false;
        }
        PlayerEngineSelection.set(player, id);
        syncTo(player);
        return true;
    }

    /** Sends the local player's personal engine choice to the server (called by the GUI). */
    public static void sendToServer(String engineId) {
        PacketDistributor.sendToServer(new SetCraftingEnginePayload(engineId));
    }

    /** Sends a request to set the global machine engine (called by the GUI). */
    public static void sendToServerGlobal(String engineId) {
        PacketDistributor.sendToServer(new SetGlobalEnginePayload(engineId));
    }

    /** Applies a new global machine default (op only). Rejects non-op players and resyncs them. */
    public static boolean applyGlobalSelection(ServerPlayer player, String id) {
        if (!player.hasPermissions(2)) {
            player.sendSystemMessage(Component.literal(
                    "[Thunderbolt Core] 无权更改全局机器引擎（需要管理员权限 2）"));
            syncTo(player);
            return false;
        }
        return applySelection(id);
    }

    /** Sends this player's personal engine choice and the machine default. */
    public static void syncTo(ServerPlayer player) {
        String machineDefault = CraftingEngineSelection.current();
        String playerChoice = PlayerEngineSelection.get(player);
        PacketDistributor.sendToPlayer(player,
                new SyncCraftingEnginePayload(
                        playerChoice == null ? Optional.empty() : Optional.of(playerChoice),
                        machineDefault));
    }

    /** Broadcasts a machine-default change (players' personal choices are untouched). */
    public static void syncToAll() {
        PacketDistributor.sendToAllPlayers(
                new SyncCraftingEnginePayload(Optional.empty(), CraftingEngineSelection.current()));
    }
}
