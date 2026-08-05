package com.moakiee.thunderbolt.api.eject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.moakiee.thunderbolt.internal.eject.EjectRegistrationSavedData;
import com.moakiee.thunderbolt.internal.eject.ThunderboltGhostOutputBlockEntity;

/**
 * Shared registry for capabilities projected from a host block entity to one or more remote faces.
 * Consumers only register endpoints; Thunderbolt owns interception, offline rejection and persistence.
 */
public final class EjectCapabilityRegistry {
    public record Entry(
            @Nullable WeakReference<? extends BlockEntity> hostRef,
            BlockEntity ghostBlockEntity,
            ResourceKey<Level> hostDimension,
            BlockPos hostPos) {
        @Nullable
        public BlockEntity getHost() {
            return hostRef != null ? hostRef.get() : null;
        }
    }

    public record DimensionPos(ResourceKey<Level> dimension, BlockPos pos) {}

    private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<EnumMap<Direction, List<Entry>>>>
            REGISTRATIONS = new IdentityHashMap<>();
    private static final ThreadLocal<int[]> BYPASS_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    @Nullable
    private static EjectRegistrationSavedData savedData;

    private EjectCapabilityRegistry() {}

    public static void setBypass(boolean bypass) {
        int[] depth = BYPASS_DEPTH.get();
        if (bypass) {
            depth[0]++;
        } else if (depth[0] > 0) {
            depth[0]--;
        }
    }

    public static boolean isBypassed() {
        return BYPASS_DEPTH.get()[0] > 0;
    }

    public static boolean isEmpty() {
        return REGISTRATIONS.isEmpty();
    }

    public static void onServerStart(MinecraftServer server) {
        savedData = EjectRegistrationSavedData.get(server);
        savedData.migrateLegacyIfNeeded(server);
        REGISTRATIONS.clear();
        BYPASS_DEPTH.remove();
        for (var registration : savedData.getAll()) {
            var targetLevel = server.getLevel(registration.interceptDimension());
            var ghost = new ThunderboltGhostOutputBlockEntity(registration.interceptPos());
            if (targetLevel != null) {
                ghost.setLevel(targetLevel);
            }
            addToMap(
                    registration.interceptDimension(),
                    registration.interceptPos().asLong(),
                    registration.interceptFace(),
                    new Entry(null, ghost, registration.hostDimension(), registration.hostPos()));
        }
    }

    public static void onServerStop() {
        savedData = null;
        REGISTRATIONS.clear();
        BYPASS_DEPTH.remove();
    }

    public static Entry createEntry(BlockEntity host, Level interceptLevel, BlockPos interceptPos) {
        var hostLevel = host.getLevel();
        if (hostLevel == null) {
            throw new IllegalStateException("Cannot register an eject host before it has a level");
        }
        var ghost = new ThunderboltGhostOutputBlockEntity(interceptPos.immutable());
        ghost.setLevel(interceptLevel);
        return new Entry(
                new WeakReference<>(host),
                ghost,
                hostLevel.dimension(),
                host.getBlockPos().immutable());
    }

    public static void register(
            ResourceKey<Level> dimension,
            long pos,
            Direction face,
            Entry entry) {
        addToMap(dimension, pos, face, entry);
        if (savedData != null) {
            savedData.add(new EjectRegistrationSavedData.PersistentRegistration(
                    dimension,
                    BlockPos.of(pos),
                    face,
                    entry.hostDimension(),
                    entry.hostPos()));
        }
    }

    public static void unregister(ResourceKey<Level> dimension, long pos, Direction face) {
        var dimensionMap = REGISTRATIONS.get(dimension);
        if (dimensionMap == null) return;
        var faceMap = dimensionMap.get(pos);
        if (faceMap == null) return;
        faceMap.remove(face);
        if (faceMap.isEmpty()) {
            dimensionMap.remove(pos);
            if (dimensionMap.isEmpty()) {
                REGISTRATIONS.remove(dimension);
            }
        }
        if (savedData != null) {
            savedData.removeByIntercept(dimension, BlockPos.of(pos), face);
        }
    }

    @Nullable
    public static Entry lookupByFace(ResourceKey<Level> dimension, long pos, Direction face) {
        var dimensionMap = REGISTRATIONS.get(dimension);
        if (dimensionMap == null) return null;
        var faceMap = dimensionMap.get(pos);
        if (faceMap == null) return null;
        return preferLoadedHost(faceMap.get(face));
    }

    @Nullable
    public static Entry lookupAny(ResourceKey<Level> dimension, long pos) {
        var dimensionMap = REGISTRATIONS.get(dimension);
        if (dimensionMap == null) return null;
        var faceMap = dimensionMap.get(pos);
        if (faceMap == null) return null;
        Entry fallback = null;
        for (var entries : faceMap.values()) {
            var candidate = preferLoadedHost(entries);
            if (candidate != null && candidate.getHost() != null) return candidate;
            if (fallback == null) fallback = candidate;
        }
        return fallback;
    }

    public static List<DimensionPos> unregisterAll(BlockEntity host, boolean persist) {
        var hostLevel = host.getLevel();
        // Server and client share these statics on an integrated server. Never
        // let a client-side lifecycle callback remove server registrations.
        if (hostLevel != null && hostLevel.isClientSide()) {
            return List.of();
        }
        ResourceKey<Level> hostDimension = hostLevel != null ? hostLevel.dimension() : null;
        BlockPos hostPos = host.getBlockPos();
        var removed = new ArrayList<DimensionPos>();

        for (var dimensionIterator = REGISTRATIONS.entrySet().iterator(); dimensionIterator.hasNext();) {
            var dimensionEntry = dimensionIterator.next();
            var positionIterator = dimensionEntry.getValue().long2ObjectEntrySet().iterator();
            while (positionIterator.hasNext()) {
                var positionEntry = positionIterator.next();
                var faceMap = positionEntry.getValue();
                boolean changed = false;
                for (var faceIterator = faceMap.entrySet().iterator(); faceIterator.hasNext();) {
                    var entries = faceIterator.next().getValue();
                    if (entries.removeIf(entry -> matchesHost(entry, host, hostDimension, hostPos))) {
                        changed = true;
                    }
                    if (entries.isEmpty()) faceIterator.remove();
                }
                if (changed) {
                    removed.add(new DimensionPos(
                            dimensionEntry.getKey(), BlockPos.of(positionEntry.getLongKey())));
                }
                if (faceMap.isEmpty()) positionIterator.remove();
            }
            if (dimensionEntry.getValue().isEmpty()) dimensionIterator.remove();
        }

        if (persist && savedData != null && hostDimension != null) {
            savedData.removeByHost(hostDimension, hostPos);
        }
        return removed;
    }

    private static void addToMap(ResourceKey<Level> dimension, long pos, Direction face, Entry entry) {
        REGISTRATIONS
                .computeIfAbsent(dimension, ignored -> new Long2ObjectOpenHashMap<>())
                .computeIfAbsent(pos, ignored -> new EnumMap<>(Direction.class))
                .computeIfAbsent(face, ignored -> new ArrayList<>())
                .add(entry);
    }

    @Nullable
    private static Entry preferLoadedHost(@Nullable List<Entry> entries) {
        if (entries == null) return null;
        Entry fallback = null;
        for (var entry : entries) {
            if (entry.getHost() != null) return entry;
            if (fallback == null) fallback = entry;
        }
        return fallback;
    }

    private static boolean matchesHost(
            Entry entry,
            BlockEntity host,
            @Nullable ResourceKey<Level> hostDimension,
            BlockPos hostPos) {
        var referencedHost = entry.getHost();
        if (referencedHost == host) return true;
        // Reloading a chunk replaces its block-entity instance. Match its stable
        // location even while the old weak reference has not yet been cleared.
        if (hostDimension != null) {
            return entry.hostDimension().equals(hostDimension)
                    && entry.hostPos().equals(hostPos);
        }
        return false;
    }
}
