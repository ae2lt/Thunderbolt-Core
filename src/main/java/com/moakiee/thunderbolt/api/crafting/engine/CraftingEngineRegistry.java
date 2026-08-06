package com.moakiee.thunderbolt.api.crafting.engine;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import net.neoforged.fml.ModList;

/**
 * 附属名单 — the registry of AE2 crafting engines (闪电 / vm / eco / …).
 *
 * <p>Thunderbolt registers its own engine ({@link #THUNDERBOLT}) on startup.
 *
 * <p><b>Optional prerequisite integration.</b> Third-party crafting mods treat Thunderbolt as an
 * <i>optional</i> prerequisite — declare it as {@code compileOnly} (no hard {@code dependencies}
 * entry in {@code neoforge.mods.toml}), detect it at runtime, and only then register:
 *
 * <pre>{@code
 * // third-party @Mod constructor
 * if (net.neoforged.fml.ModList.get().isLoaded(CraftingEngineRegistry.MODID)) {
 *     CraftingEngineRegistry.register(new MyEngine());
 * }
 * }</pre>
 *
 * <p>The {@code isLoaded} guard keeps the optional dependency safe: Thunderbolt classes are only
 * resolved on the guarded branch (lazy class resolution), so the third-party mod keeps working
 * unchanged when Thunderbolt is not installed.
 *
 * <p>Registration is idempotent per id (a later registration replaces the earlier one) and
 * thread-safe. The owning mod id gates availability through {@link ModList}.
 */
public final class CraftingEngineRegistry {

    public static final String MODID = "thunderbolt";
    public static final String NONE = "none";
    public static final String THUNDERBOLT = "thunderbolt";

    private static final List<CraftingEngine> ENGINES = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean THUNDERBOLT_REGISTERED = new AtomicBoolean();

    private CraftingEngineRegistry() {
    }

    /** Registers Thunderbolt's own engine exactly once. */
    public static void registerThunderbolt(CraftingEngine engine) {
        if (THUNDERBOLT_REGISTERED.compareAndSet(false, true)) {
            register(engine);
        }
    }

    public static void register(CraftingEngine engine) {
        if (engine == null || engine.id() == null || engine.id().isBlank()) {
            throw new IllegalArgumentException("engine id must not be blank");
        }
        ENGINES.removeIf(e -> e.id().equals(engine.id()));
        ENGINES.add(engine);
    }

    public static Optional<CraftingEngine> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return ENGINES.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    /** All registered engines (regardless of whether the owning mod is loaded). */
    public static List<CraftingEngine> engines() {
        return List.copyOf(ENGINES);
    }

    /**
     * Engines that can be selected right now: registered AND their owning mod is loaded
     * (modId == null means always present).
     */
    public static List<CraftingEngine> available() {
        return ENGINES.stream().filter(CraftingEngineRegistry::isEngineLoaded).toList();
    }

    public static boolean isAvailable(String id) {
        return byId(id).map(CraftingEngineRegistry::isEngineLoaded).orElse(false);
    }

    private static boolean isEngineLoaded(CraftingEngine engine) {
        String modId = engine.modId();
        if (modId == null || modId.isBlank()) {
            return true;
        }
        try {
            return ModList.get() != null && ModList.get().isLoaded(modId);
        } catch (RuntimeException ignored) {
            // Non-standard loader without a mod list: don't hide the engine.
            return true;
        }
    }
}
