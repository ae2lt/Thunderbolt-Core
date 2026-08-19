package com.moakiee.thunderbolt;

import java.util.Collection;

import com.mojang.logging.LogUtils;
import com.moakiee.thunderbolt.core.util.FastWildcardMatcher;
import org.slf4j.Logger;

/** Lightweight host-configured values shared by Thunderbolt's low-level hooks. */
public final class CoreConfig {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Channel capacity granted per registered high-capacity controller by the channel grid mixins.
     * Defaults to 128; the host mod (AE2 Lightning Tech) overwrites it from its own config during
     * setup via {@link #setChannelsPerController(int)} so the value stays user-configurable.
     */
    private static volatile int channelsPerController = 128;
    private static volatile BatchCopyLimitRules batchCopyLimitRules =
            new BatchCopyLimitRules(0L, FastWildcardMatcher.empty());

    public static int channelsPerController() {
        return channelsPerController;
    }

    public static void setChannelsPerController(int value) {
        // 该值作为每个控制器的渠道容量注入 Dinic 最大流计算，
        // 0 或负数会导致全网渠道瘫痪或未定义行为，必须钳制到至少 1。
        int clamped = Math.max(1, value);
        if (value != clamped) {
            LOGGER.warn("非法的 channelsPerController 配置值: {}，已钳制为 {}", value, clamped);
        }
        channelsPerController = clamped;
    }

    /**
     * Returns the immutable, versioned block matcher used by hot-path batch targets.
     * Callers may cache the result until {@link BatchCopyLimitRules#version()} changes.
     */
    public static BatchCopyLimitRules batchCopyLimitRules() {
        return batchCopyLimitRules;
    }

    public static synchronized void setBatchCopyLimitedBlocks(
            Collection<? extends String> patterns) {
        var current = batchCopyLimitRules;
        batchCopyLimitRules = new BatchCopyLimitRules(
                current.version() + 1L,
                FastWildcardMatcher.compile(patterns));
    }

    public record BatchCopyLimitRules(long version, FastWildcardMatcher matcher) {
        public static final int MATCHED_MAX_COPIES = 1024;

        public boolean matches(String blockId) {
            return matcher.matches(blockId);
        }

        public long limit(String blockId) {
            return matches(blockId) ? MATCHED_MAX_COPIES : Long.MAX_VALUE;
        }
    }

    private CoreConfig() {
    }
}
