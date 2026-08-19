package com.moakiee.thunderbolt.core.eject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/** Internal persistence for {@code EjectCapabilityRegistry}. */
public final class EjectRegistrationSavedData extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DATA_NAME = "thunderbolt_eject_registrations";
    private static final String LEGACY_DATA_NAME = "ae2lt_eject_registrations";
    private static final String TAG_ENTRIES = "Entries";
    private static final String TAG_LEGACY_MIGRATION_COMPLETE = "LegacyMigrationComplete";
    private static final String TAG_I_DIM = "IDim";
    private static final String TAG_I_POS = "IPos";
    private static final String TAG_I_FACE = "IFace";
    private static final String TAG_P_DIM = "PDim";
    private static final String TAG_P_POS = "PPos";

    public record PersistentRegistration(
            ResourceKey<Level> interceptDimension,
            BlockPos interceptPos,
            Direction interceptFace,
            ResourceKey<Level> hostDimension,
            BlockPos hostPos) {}

    private final List<PersistentRegistration> entries = new ArrayList<>();
    private boolean legacyMigrationComplete;

    public static EjectRegistrationSavedData get(MinecraftServer server) {
        // Forge 1.20.1: no SavedData.Factory — computeIfAbsent(loader, factory, name).
        return server.overworld().getDataStorage().computeIfAbsent(
                EjectRegistrationSavedData::load,
                EjectRegistrationSavedData::new,
                DATA_NAME);
    }

    /**
     * Imports the old AE2LT data file once without modifying it. The completion marker is persisted
     * even for an empty source so intentionally-cleared registrations are never resurrected later.
     */
    public void migrateLegacyIfNeeded(MinecraftServer server) {
        if (legacyMigrationComplete) return;
        // Forge 1.20.1 has no 2-arg computeIfAbsent; the loader always yields an instance, so
        // guard by contents instead of nullability.
        var legacy = server.overworld().getDataStorage().computeIfAbsent(
                EjectRegistrationSavedData::load,
                EjectRegistrationSavedData::new,
                LEGACY_DATA_NAME);
        if (!legacy.entries.isEmpty()) {
            for (var registration : legacy.entries) {
                if (!entries.contains(registration)) entries.add(registration);
            }
        }
        legacyMigrationComplete = true;
        setDirty();
    }

    public List<PersistentRegistration> getAll() {
        return Collections.unmodifiableList(entries);
    }

    public void add(PersistentRegistration registration) {
        if (!entries.contains(registration)) {
            entries.add(registration);
            setDirty();
        }
    }

    public void removeByIntercept(ResourceKey<Level> dimension, BlockPos pos, Direction face) {
        long packedPos = pos.asLong();
        if (entries.removeIf(entry -> entry.interceptDimension().equals(dimension)
                && entry.interceptPos().asLong() == packedPos
                && entry.interceptFace() == face)) {
            setDirty();
        }
    }

    public void removeByHost(ResourceKey<Level> dimension, BlockPos pos) {
        if (entries.removeIf(entry -> entry.hostDimension().equals(dimension)
                && entry.hostPos().equals(pos))) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        var list = new ListTag();
        for (var entry : entries) {
            var encoded = new CompoundTag();
            encoded.putString(TAG_I_DIM, entry.interceptDimension().location().toString());
            encoded.putLong(TAG_I_POS, entry.interceptPos().asLong());
            encoded.putInt(TAG_I_FACE, entry.interceptFace().get3DDataValue());
            encoded.putString(TAG_P_DIM, entry.hostDimension().location().toString());
            encoded.putLong(TAG_P_POS, entry.hostPos().asLong());
            list.add(encoded);
        }
        tag.put(TAG_ENTRIES, list);
        tag.putBoolean(TAG_LEGACY_MIGRATION_COMPLETE, legacyMigrationComplete);
        return tag;
    }

    static EjectRegistrationSavedData load(CompoundTag tag) {
        var data = new EjectRegistrationSavedData();
        data.legacyMigrationComplete = tag.getBoolean(TAG_LEGACY_MIGRATION_COMPLETE);
        if (!tag.contains(TAG_ENTRIES, Tag.TAG_LIST)) return data;
        var list = tag.getList(TAG_ENTRIES, Tag.TAG_COMPOUND);
        boolean healed = false;
        for (int i = 0; i < list.size(); i++) {
            var encoded = list.getCompound(i);
            if (!hasRequiredFields(encoded)) {
                LOGGER.warn("跳过缺少必需字段的 eject 注册条目: {}", encoded);
                healed = true;
                continue;
            }
            // 畸形维度 id 在 tryParse 处返回 null，此时跳过整条记录并记日志，
            // 而不是把 null 传入 ResourceKey.create 让整个 SavedData load 抛 NPE。
            var interceptDimension = tryDimension(encoded.getString(TAG_I_DIM));
            var hostDimension = tryDimension(encoded.getString(TAG_P_DIM));
            if (interceptDimension.isEmpty() || hostDimension.isEmpty()) {
                healed = true;
                continue;
            }
            var interceptPos = BlockPos.of(encoded.getLong(TAG_I_POS));
            var hostPos = BlockPos.of(encoded.getLong(TAG_P_POS));
            // 损坏的 long 可能被解出超出原版建筑高度范围的 y（BlockPos.of 本身不抛异常），
            // 这类坐标在游戏中永远无法命中，统一跳过并记日志。
            if (!isWithinBuildHeight(interceptPos) || !isWithinBuildHeight(hostPos)) {
                LOGGER.warn("跳过畸形坐标的 eject 注册条目: {} / {}", interceptPos, hostPos);
                healed = true;
                continue;
            }
            int rawFace = encoded.getInt(TAG_I_FACE);
            if (rawFace < 0 || rawFace >= Direction.values().length) {
                LOGGER.warn("跳过非法方向值的 eject 注册条目: {}", rawFace);
                healed = true;
                continue;
            }
            var registration = new PersistentRegistration(
                    interceptDimension.get(),
                    interceptPos,
                    Direction.from3DDataValue(rawFace),
                    hostDimension.get(),
                    hostPos);
            if (!data.entries.contains(registration)) {
                data.entries.add(registration);
            } else {
                healed = true;
            }
        }
        if (healed) data.setDirty();
        return data;
    }

    private static boolean hasRequiredFields(CompoundTag tag) {
        return tag.contains(TAG_I_DIM, Tag.TAG_STRING)
                && tag.contains(TAG_I_POS, Tag.TAG_LONG)
                && tag.contains(TAG_I_FACE, Tag.TAG_INT)
                && tag.contains(TAG_P_DIM, Tag.TAG_STRING)
                && tag.contains(TAG_P_POS, Tag.TAG_LONG);
    }

    /**
     * y 超出原版建筑高度范围视为畸形数据。
     * 对应原版 LevelHeightMinMax 的 MIN_BUILD_HEIGHT(-2048) / MAX_BUILD_HEIGHT(2048)，
     * 此处用字面量避免对具体常量位置的依赖。
     */
    private static boolean isWithinBuildHeight(BlockPos pos) {
        return pos.getY() >= -2048 && pos.getY() < 2048;
    }

    /**
     * {@link #dimension(String)} 的容错入口：id 无法解析时记 warn 日志并返回空，
     * 由调用方跳过该条目，避免单条损坏数据拖垮整个 load。
     */
    static Optional<ResourceKey<Level>> tryDimension(String id) {
        if (ResourceLocation.tryParse(id) == null) {
            LOGGER.warn("跳过畸形的维度 id 条目: {}", id);
            return Optional.empty();
        }
        return Optional.of(dimension(id));
    }

    /**
     * Builds a {@link ResourceKey<Level>} from its string id. 1.20.1's
     * {@code Registries.DIMENSION} is only initialised after {@code BuiltInRegistries} is
     * bootstrapped, which plain JUnit cannot do, so the key is built via the private factory
     * instead. The registry name ({@code minecraft:dimension}) matches
     * {@code Registries.DIMENSION.location()} exactly, so keys compare equal to runtime ones.
     *
     * <p>The factory is private and its reflective lookup must try both names: {@code create}
     * (dev, mojmap) and {@code m_135790_} (release, srg) — the method is renamed at obfuscation
     * and the reflective string is never remapped.
     */
    @SuppressWarnings("unchecked")
    static ResourceKey<Level> dimension(String id) {
        try {
            return (ResourceKey<Level>) RESOURCE_KEY_CREATE.invoke(null,
                    new ResourceLocation("minecraft", "dimension"),
                    ResourceLocation.tryParse(id));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to build dimension key " + id, e);
        }
    }

    private static final Method RESOURCE_KEY_CREATE = reflectResourceKeyCreate();

    private static Method reflectResourceKeyCreate() {
        for (String name : new String[]{"create", "m_135790_"}) {
            try {
                var create = ResourceKey.class.getDeclaredMethod(
                        name, ResourceLocation.class, ResourceLocation.class);
                create.setAccessible(true);
                return create;
            } catch (NoSuchMethodException ignored) {
                // try the other mapping
            }
        }
        throw new ExceptionInInitializerError(new NoSuchMethodException(
                "ResourceKey.create(ResourceLocation, ResourceLocation)"));
    }
}
