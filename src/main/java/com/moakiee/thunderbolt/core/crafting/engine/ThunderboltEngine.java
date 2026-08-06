package com.moakiee.thunderbolt.core.crafting.engine;

// [Thunderbolt-Core] engine-selection + mixin-package-fixes changeset (PR -> refactor/thunderbolt-three-layer-clean, 2026-08-07)

import java.util.concurrent.Future;

import appeng.api.networking.crafting.ICraftingPlan;

import com.moakiee.thunderbolt.api.crafting.engine.CraftingEngine;
import com.moakiee.thunderbolt.api.crafting.engine.CraftingEngineRegistry;
import com.moakiee.thunderbolt.api.crafting.engine.CraftingEngineRequest;

public final class ThunderboltEngine implements CraftingEngine {

    @Override
    public String id() {
        return CraftingEngineRegistry.THUNDERBOLT;
    }

    @Override
    public String displayName() {
        return "闪电";
    }

    @Override
    public String modId() {
        return null;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public Future<ICraftingPlan> route(CraftingEngineRequest request) {
        return request.nativeInvoker().callNative(
                request.level(), request.requester(), request.what(), request.amount(), request.strategy());
    }
}
