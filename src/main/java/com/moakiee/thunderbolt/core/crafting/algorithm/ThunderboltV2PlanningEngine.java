package com.moakiee.thunderbolt.core.crafting.algorithm;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingPlan;
import net.minecraft.resources.ResourceLocation;

import com.moakiee.thunderbolt.ThunderboltCore;
import com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngine;
import com.moakiee.thunderbolt.api.crafting.PlanningAttempt;
import com.moakiee.thunderbolt.api.crafting.PlanningEngineSession;
import com.moakiee.thunderbolt.api.crafting.PlanningRequest;
import com.moakiee.thunderbolt.api.crafting.planner.CraftingInventoryView;
import com.moakiee.thunderbolt.api.crafting.planner.CraftingPlannerRequest;
import com.moakiee.thunderbolt.api.crafting.planner.CraftingPlannerStatus;
import com.moakiee.thunderbolt.core.crafting.planner.PlannerDispatch;
import com.moakiee.thunderbolt.core.crafting.planner.PlanningMetadataStore;
import com.moakiee.thunderbolt.core.crafting.planner.ReusableStockUsageKey;
import com.moakiee.thunderbolt.core.crafting.support.CraftingStockPolicy;
import com.moakiee.thunderbolt.core.crafting.support.FastCraftingPlanner;

/** Adapter that exposes Thunderbolt's V2 planner through the multi-algorithm API. */
public final class ThunderboltV2PlanningEngine implements CraftingPlanningEngine {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            ThunderboltCore.MODID, "v2");
    public static final ThunderboltV2PlanningEngine INSTANCE = new ThunderboltV2PlanningEngine();

    private ThunderboltV2PlanningEngine() {
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public boolean check(IGrid grid, PlanningRequest request) {
        return request.requestedAmount() > 0
                && request.output() != null
                && request.requester().getGridNode() != null
                && request.requester().getGridNode().getGrid() == grid;
    }

    @Override
    public PlanningEngineSession createSession(IGrid grid, PlanningRequest request) {
        return new Session(request);
    }

    private static final class Session implements PlanningEngineSession {
        private final PlanningRequest request;
        private final Map<CraftingPlan, Map<ReusableStockUsageKey<AEKey>, Long>> reusableStock =
                new IdentityHashMap<>();

        private Session(PlanningRequest request) {
            this.request = request;
        }

        @Override
        public PlanningAttempt attempt(long amount, boolean simulate) {
            var plannerSelection = PlannerDispatch.dispatch(new CraftingPlannerRequest(
                    request.craftingService(),
                    request.requester(),
                    new CraftingInventoryView() {
                        @Override
                        public long available(AEKey key) {
                            return Math.max(0L, request.networkInventory().extract(
                                    key, Long.MAX_VALUE, Actionable.SIMULATE));
                        }

                        @Override
                        public java.util.List<AEKey> fuzzyCandidates(AEKey template) {
                            var candidates = new ArrayList<AEKey>();
                            for (var candidate : request.networkInventory().findFuzzyTemplates(template)) {
                                candidates.add(candidate);
                            }
                            return java.util.List.copyOf(candidates);
                        }
                    },
                    request.level(), request.output(), amount, simulate));
            if (plannerSelection.handled()) {
                var plannerResult = plannerSelection.result();
                if (plannerResult.plan() != null
                        && !(plannerResult.plan() instanceof CraftingPlan)) {
                    throw new IllegalStateException("Planner " + plannerSelection.plannerId()
                            + " returned a plan AE2 cannot use for runCraftAttempt: "
                            + plannerResult.plan().getClass().getName());
                }
                var plannerPlan = (CraftingPlan) plannerResult.plan();
                if (plannerResult.status() == CraftingPlannerStatus.EXACT_INFEASIBLE && !simulate) {
                    return PlanningAttempt.infeasible(plannerPlan);
                }
                if (plannerPlan != null) {
                    return PlanningAttempt.handled(plannerPlan);
                }
                ThunderboltCore.LOGGER.warn(
                        "[Thunderbolt Core] terminal planner {} returned {} without a usable plan; "
                                + "trying the next algorithm",
                        plannerSelection.plannerId(), plannerResult.status());
                return PlanningAttempt.DECLINE;
            }

            var result = FastCraftingPlanner.tryAttempt(
                    request.craftingService(), request.networkInventory(), request.level(),
                    request.output(), amount, simulate,
                    request.requester() instanceof CraftingStockPolicy policy ? policy : null);
            if (!result.handled()) {
                return PlanningAttempt.DECLINE;
            }
            if (result.plan() != null) {
                reusableStock.put(result.plan(), result.usedReusableStock());
            }
            if (result.simulationFallback() != null) {
                reusableStock.put(result.simulationFallback(), result.usedReusableStock());
            }
            return new PlanningAttempt(
                    PlanningAttempt.Status.HANDLED,
                    result.plan(),
                    result.simulationFallback());
        }

        @Override
        public ICraftingPlan finish(ICraftingPlan result) {
            if (result instanceof CraftingPlan craftingPlan) {
                var used = reusableStock.get(craftingPlan);
                if (used != null) {
                    PlanningMetadataStore.record(craftingPlan, used);
                }
            }
            reusableStock.clear();
            return result;
        }
    }
}
