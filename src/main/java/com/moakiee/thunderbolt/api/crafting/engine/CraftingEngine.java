package com.moakiee.thunderbolt.api.crafting.engine;

import java.util.concurrent.Future;

import appeng.api.networking.crafting.ICraftingPlan;

/**
 * A registered AE2 crafting-calculation engine (附属名单中的一员).
 *
 * <p>Third-party crafting mods (an AE2 VM interpreter, a reworked planner, an ECO-style host, …)
 * opt in by implementing this interface and calling
 * {@link CraftingEngineRegistry#register(CraftingEngine)} from their own {@code @Mod} constructor —
 * guarded by {@code ModList.get().isLoaded(CraftingEngineRegistry.MODID)} so Thunderbolt stays an
 * <b>optional prerequisite</b>. Once registered, the engine appears in the shared mutually-exclusive
 * selection
 * (选择按钮, {@link CraftingEngineSelection}) and, when selected, receives every
 * {@code CraftingService#beginCraftingCalculation} request first — before any un-cooperating
 * mod's own mixin runs.
 *
 * <p>Engines are mutually exclusive: at most one is selected at a time. An engine that cannot
 * handle a request returns {@code null} from {@link #route(CraftingEngineRequest)}, which falls
 * through to the original AE2 calculation (and to any other installed mod's own hook).
 */
public interface CraftingEngine {

    /** Stable id used in the selection (e.g. "thunderbolt", "vm", "eco"). */
    String id();

    /** Human-readable name shown in the selection screen (e.g. "闪电", "AE2 VM", "NeoECO"). */
    String displayName();

    /**
     * Owning mod id used for load detection. Return {@code null} if the engine is always present
     * (e.g. Thunderbolt's own engine).
     */
    String modId();

    /** @return whether this engine is currently enabled (its own on/off switch). */
    boolean isEnabled();

    /**
     * Attempt to handle one crafting calculation.
     *
     * @param request the immutable request snapshot, including a guarded native fallback invoker
     * @return the resulting future to take over (the caller then cancels the remaining first-layer
     *         mixins), or {@code null} to fall through to the original path
     */
    Future<ICraftingPlan> route(CraftingEngineRequest request);
}
