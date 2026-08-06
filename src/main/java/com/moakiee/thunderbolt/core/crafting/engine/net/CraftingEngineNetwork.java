package com.moakiee.thunderbolt.core.crafting.engine.net;

// [Thunderbolt-Core] engine-selection + mixin-package-fixes changeset (PR -> refactor/thunderbolt-three-layer-clean, 2026-08-07)

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import com.moakiee.thunderbolt.api.crafting.engine.CraftingEngineSelection;
import com.moakiee.thunderbolt.core.crafting.engine.CraftingEngineConfig;

public final class CraftingEngineNetwork {

    private CraftingEngineNetwork() {
    }

    /** Client → server: change the selected crafting engine. */
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

    /** Server → client: the currently selected engine (on login and after every change). */
    public record SyncCraftingEnginePayload(String engineId) implements CustomPacketPayload {
        public static final Type<SyncCraftingEnginePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath("thunderbolt", "sync_crafting_engine"));
        public static final StreamCodec<ByteBuf, SyncCraftingEnginePayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, SyncCraftingEnginePayload::engineId,
                SyncCraftingEnginePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("thunderbolt");

        registrar.playToServer(SetCraftingEnginePayload.TYPE, SetCraftingEnginePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        applySelection(payload.engineId())));

        registrar.playToClient(SyncCraftingEnginePayload.TYPE, SyncCraftingEnginePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        CraftingEngineSelection.seed(payload.engineId())));
    }

    /** Applies the selection on the server and broadcasts the new state to all clients. */
    public static boolean applySelection(String id) {
        if (!CraftingEngineConfig.set(id)) {
            return false;
        }
        syncToAll();
        return true;
    }

    /** Sends the current selection from the client to the server (called by the GUI). */
    public static void sendToServer(String engineId) {
        PacketDistributor.sendToServer(new SetCraftingEnginePayload(engineId));
    }

    public static void syncTo(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new SyncCraftingEnginePayload(CraftingEngineSelection.current()));
    }

    public static void syncToAll() {
        PacketDistributor.sendToAllPlayers(new SyncCraftingEnginePayload(CraftingEngineSelection.current()));
    }
}
