package com.moakiee.thunderbolt.ae2.overload.pattern;

import java.util.Objects;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import com.moakiee.thunderbolt.ae2.overload.model.EncodedOverloadPattern;
import com.moakiee.thunderbolt.ae2.overload.model.MatchMode;
import com.moakiee.thunderbolt.ae2.overload.model.OverloadPatternSlot;

/**
 * NBT bridge for overload-pattern item payloads.
 * <p>
 * This keeps item persistence concerns out of the model objects themselves.
 */
public final class OverloadPatternPayloadTagCodec {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG_HOST_KIND = "HostKind";
    private static final String TAG_SOURCE_PATTERN = "SourcePattern";
    private static final String TAG_RULES = "Rules";
    private static final String TAG_INPUTS = "Inputs";
    private static final String TAG_OUTPUTS = "Outputs";
    private static final String TAG_SLOT = "Slot";
    private static final String TAG_MODE = "Mode";

    private OverloadPatternPayloadTagCodec() {
    }

    public static CompoundTag writePayload(OverloadPatternPayload payload) {
        Objects.requireNonNull(payload, "payload");

        var tag = new CompoundTag();
        tag.putString(TAG_HOST_KIND, payload.requiredHostKind().name());
        tag.put(TAG_SOURCE_PATTERN, payload.sourcePattern().toTag());
        tag.put(TAG_RULES, writeEncodedPattern(payload.encodedPattern()));
        return tag;
    }

    public static OverloadPatternPayload readPayload(CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");

        var hostKind = tryParse(
                PatternExecutionHostKind.class,
                tag.getString(TAG_HOST_KIND),
                PatternExecutionHostKind.OVERLOADED_PATTERN_PROVIDER);
        var sourcePattern = tryReadSourcePattern(tag.getCompound(TAG_SOURCE_PATTERN));
        var encodedPattern = readEncodedPattern(tag.getCompound(TAG_RULES));
        return new OverloadPatternPayload(hostKind, sourcePattern, encodedPattern);
    }

    public static CompoundTag writeEncodedPattern(EncodedOverloadPattern encodedPattern) {
        Objects.requireNonNull(encodedPattern, "encodedPattern");

        var tag = new CompoundTag();
        tag.put(TAG_INPUTS, writeSlots(encodedPattern.inputSlots()));
        tag.put(TAG_OUTPUTS, writeSlots(encodedPattern.outputSlots()));
        return tag;
    }

    public static EncodedOverloadPattern readEncodedPattern(CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");

        var builder = EncodedOverloadPattern.builder();

        if (tag.contains(TAG_INPUTS, Tag.TAG_LIST)) {
            var inputs = tag.getList(TAG_INPUTS, Tag.TAG_COMPOUND);
            for (int i = 0; i < inputs.size(); i++) {
                var slotTag = inputs.getCompound(i);
                int slotIndex = slotTag.getInt(TAG_SLOT);
                try {
                    builder.input(
                            slotIndex,
                            tryParse(MatchMode.class, slotTag.getString(TAG_MODE), MatchMode.STRICT));
                } catch (IllegalArgumentException malformed) {
                    // 重复槽位或负槽位等坏条目：跳过单条并记日志，不让单个坏条目
                    // 使整份 payload 读取失败（与枚举容错同一设计目标）。
                    LOGGER.warn("Skipping malformed overload pattern input slot entry {} ({}).",
                            slotIndex, malformed.getMessage());
                }
            }
        }

        if (tag.contains(TAG_OUTPUTS, Tag.TAG_LIST)) {
            var outputs = tag.getList(TAG_OUTPUTS, Tag.TAG_COMPOUND);
            for (int i = 0; i < outputs.size(); i++) {
                var slotTag = outputs.getCompound(i);
                int slotIndex = slotTag.getInt(TAG_SLOT);
                try {
                    builder.output(
                            slotIndex,
                            tryParse(MatchMode.class, slotTag.getString(TAG_MODE), MatchMode.STRICT));
                } catch (IllegalArgumentException malformed) {
                    LOGGER.warn("Skipping malformed overload pattern output slot entry {} ({}).",
                            slotIndex, malformed.getMessage());
                }
            }
        }

        return builder.build();
    }

    /**
     * 容错解析枚举：物品 NBT 可能被损坏、手改或在模组版本更迭后残留未知枚举名，
     * {@code Enum.valueOf} 会对其抛出未捕获的 IllegalArgumentException 并使整份
     * payload 读取失败。此处降级为语义最保守的默认值并记录一次警告，保证读取
     * 路径不再因单个坏值而崩溃。
     */
    private static <E extends Enum<E>> E tryParse(Class<E> type, String name, E fallback) {
        if (name == null || name.isEmpty()) {
            LOGGER.warn("Missing {} value in overload pattern payload; falling back to {}.",
                    type.getSimpleName(), fallback);
            return fallback;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException unknown) {
            LOGGER.warn("Unknown {} value '{}' in overload pattern payload; falling back to {}.",
                    type.getSimpleName(), name, fallback);
            return fallback;
        }
    }

    /**
     * 容错读取源图案快照：缺失 item id 或 id 字符串非法时，
     * {@link SourcePatternSnapshot#fromTag} 会抛 IllegalArgumentException。
     * 该读取路径在物品解码/合成计算中被频繁调用（例如 AE2LT 的
     * {@code OverloadPatternItem.readPayload} 未包 try/catch），单个损坏物品
     * 不应拖垮整个读取流程。此处降级为指向 {@code minecraft:air} 的惰性快照：
     * 下游按普通图案重解析时会得到空堆/无法解码的图案，该过载图案表现为
     * 惰性不匹配，而非崩溃。
     */
    private static SourcePatternSnapshot tryReadSourcePattern(CompoundTag tag) {
        try {
            return SourcePatternSnapshot.fromTag(tag);
        } catch (RuntimeException malformed) {
            LOGGER.warn("Malformed source pattern snapshot in overload pattern payload; "
                    + "falling back to an inert air snapshot.", malformed);
            return new SourcePatternSnapshot(new ResourceLocation("minecraft", "air"), null, null);
        }
    }

    private static ListTag writeSlots(Iterable<OverloadPatternSlot> slots) {
        var list = new ListTag();
        for (var slot : slots) {
            var slotTag = new CompoundTag();
            slotTag.putInt(TAG_SLOT, slot.slotIndex());
            slotTag.putString(TAG_MODE, slot.matchMode().name());
            list.add(slotTag);
        }
        return list;
    }
}
