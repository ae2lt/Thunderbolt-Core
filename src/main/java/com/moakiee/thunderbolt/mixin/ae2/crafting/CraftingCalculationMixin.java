package com.moakiee.thunderbolt.mixin.ae2.crafting;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.NetworkCraftingSimulationState;

import com.moakiee.thunderbolt.ThunderboltCore;
import com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngines;
import com.moakiee.thunderbolt.api.crafting.PlanningAttempt;
import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningChoice;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningEngineSession;
import com.moakiee.thunderbolt.api.crafting.PlanningRequest;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import com.moakiee.thunderbolt.core.crafting.algorithm.CraftingAlgorithmCalculationStatus;
import com.moakiee.thunderbolt.core.crafting.algorithm.PlanningCandidateExecutor;
import com.moakiee.thunderbolt.core.crafting.algorithm.PlanningCandidateDeclinedException;
import com.moakiee.thunderbolt.core.crafting.algorithm.PlanningFailurePlans;
import com.moakiee.thunderbolt.core.crafting.plan.LoopCraftingPlan;
import com.moakiee.thunderbolt.core.crafting.planner.PlanningMetadataStore;

/** Runs complete calculations in policy order and locks the first successful candidate. */
@Mixin(value = CraftingCalculation.class, remap = false)
public abstract class CraftingCalculationMixin implements CraftingPlanningControl {
    @Shadow
    @Final
    private NetworkCraftingSimulationState networkInv;

    @Shadow
    @Final
    private AEKey output;

    @Shadow
    @Final
    private long requestedAmount;

    @Shadow
    @Final
    private CalculationStrategy strategy;

    @Shadow
    @Final
    ICraftingSimulationRequester simRequester;

    @Shadow
    private boolean simulate;

    @Shadow
    abstract net.minecraft.world.level.Level getLevel();

    @Shadow
    abstract void handlePausing() throws InterruptedException;

    @Unique
    private List<CapturedPlanningChoice> thunderbolt$candidates =
            List.of(CapturedPlanningChoice.vanilla());

    @Unique
    @Nullable
    private PlanningRequest thunderbolt$request;

    @Unique
    private volatile boolean thunderbolt$activeVanilla;

    @Unique
    @Nullable
    private net.minecraft.resources.ResourceLocation thunderbolt$selectedEngine;

    @Unique
    private boolean thunderbolt$selectedVanilla;

    @Unique
    private long thunderbolt$calculationStartedNanos;

    @Unique
    private final AtomicInteger thunderbolt$attempts = new AtomicInteger();

    @Unique
    private final AtomicInteger thunderbolt$handledAttempts = new AtomicInteger();

    @Unique
    private final AtomicInteger thunderbolt$declinedEngines = new AtomicInteger();

    @Override
    public void thunderbolt$configurePlanning(
            List<PlanningChoice> candidates, IGrid grid) {
        if (candidates == null || candidates.isEmpty()
                || candidates.getLast().kind() != PlanningChoice.Kind.VANILLA) {
            throw new IllegalArgumentException("Resolved planning candidates must end in VANILLA");
        }
        var request = new PlanningRequest(
                getLevel(), grid.getCraftingService(), networkInv, output,
                requestedAmount, strategy, simRequester);
        var captured = new ArrayList<CapturedPlanningChoice>(candidates.size());
        for (var choice : candidates) {
            if (choice.kind() == PlanningChoice.Kind.VANILLA) {
                captured.add(CapturedPlanningChoice.vanilla());
                continue;
            }
            var engine = CraftingPlanningEngines.get(choice.engineId());
            if (engine == null) {
                continue;
            }
            try {
                if (engine.check(grid, request)) {
                    captured.add(new CapturedPlanningChoice(
                            choice, engine, engine.capture(grid, request)));
                }
            } catch (RuntimeException failure) {
                ThunderboltCore.LOGGER.warn(
                        "[Thunderbolt Core] planning candidate preparation failed; skipping: "
                                + "engine={} output={}",
                        choice.engineId(), output, failure);
            }
        }
        this.thunderbolt$request = request;
        this.thunderbolt$candidates = List.copyOf(captured);
    }

    @Inject(method = "run", at = @At("HEAD"), remap = false)
    private void thunderbolt$startCalculation(CallbackInfoReturnable<ICraftingPlan> cir) {
        thunderbolt$calculationStartedNanos = System.nanoTime();
        thunderbolt$attempts.set(0);
        thunderbolt$handledAttempts.set(0);
        thunderbolt$declinedEngines.set(0);
        thunderbolt$activeVanilla = false;
        thunderbolt$selectedEngine = null;
        thunderbolt$selectedVanilla = false;

        ThunderboltCore.LOGGER.info(
                "[Thunderbolt Core][crafting-planner] started: output={} requested={} candidates={}",
                output, requestedAmount,
                thunderbolt$candidates.stream().map(CapturedPlanningChoice::choice).toList());
    }

    @WrapOperation(
            method = "run",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/crafting/CraftingCalculation;"
                            + "computePlan()Lappeng/api/networking/crafting/ICraftingPlan;"))
    private ICraftingPlan thunderbolt$routeCompleteCalculation(
            CraftingCalculation instance, Operation<ICraftingPlan> original) {
        for (var candidate : thunderbolt$candidates) {
            var choice = candidate.choice();
            var engineId = choice.kind() == PlanningChoice.Kind.VANILLA
                    ? CraftingPlanningEngines.VANILLA_ID
                    : choice.engineId();
            boolean timedOut = false;
            try {
                var engine = candidate.engine();
                var candidateRequest = thunderbolt$request;
                if (choice.kind() == PlanningChoice.Kind.ENGINE
                        && (engine == null || candidateRequest == null)) {
                    thunderbolt$declinedEngines.incrementAndGet();
                    continue;
                }
                thunderbolt$activeVanilla = choice.kind() == PlanningChoice.Kind.VANILLA;
                var execution = PlanningCandidateExecutor.execute(
                        engineId,
                        "output=" + output + " requested=" + requestedAmount,
                        context -> choice.kind() == PlanningChoice.Kind.VANILLA
                                ? thunderbolt$runVanillaCandidate(instance, original, context)
                                : thunderbolt$runEngineCandidate(
                                        engine, candidateRequest, candidate.capturedInput(), context),
                        this::thunderbolt$yieldToAe2Scheduler,
                        CraftingCalculationMixin::thunderbolt$discardCandidatePlan);
                if (execution.status() == PlanningCandidateExecutor.Status.QUARANTINED) {
                    thunderbolt$declinedEngines.incrementAndGet();
                    continue;
                }
                timedOut = execution.status() == PlanningCandidateExecutor.Status.SOFT_TIMEOUT
                        || execution.status() == PlanningCandidateExecutor.Status.HARD_TIMEOUT;
                if (execution.status() != PlanningCandidateExecutor.Status.SUCCESS) {
                    Throwable failure = execution.failure();
                    if (failure == null) {
                        failure = new PlanningExitException(
                                "planning candidate exceeded timeout grace: " + engineId);
                    }
                    if (failure instanceof Error) {
                        return thunderbolt$rethrow(failure);
                    }
                    if (failure instanceof RuntimeException runtimeFailure) {
                        throw runtimeFailure;
                    }
                    throw new RuntimeException(failure);
                }
                ICraftingPlan result = execution.value();

                if (result == null) {
                    throw new PlanningCandidateDeclinedException();
                }

                this.simulate = result.simulation();
                thunderbolt$selectedVanilla = choice.kind() == PlanningChoice.Kind.VANILLA;
                thunderbolt$selectedEngine = engineId;
                CraftingAlgorithmCalculationStatus.select(simRequester, engineId);
                return result;
            } catch (Throwable failure) {
                if (failure instanceof Error
                        || (!timedOut && (failure instanceof CancellationException
                        || !(failure instanceof RuntimeException)))) {
                    return thunderbolt$rethrow(failure);
                }
                thunderbolt$declinedEngines.incrementAndGet();
                if (!(failure instanceof PlanningCandidateDeclinedException)) {
                    ThunderboltCore.LOGGER.warn(
                            "[Thunderbolt Core] planning candidate failed; trying next: "
                                    + "engine={} output={} timeout={}",
                            engineId, output, timedOut, failure);
                }
            } finally {
                thunderbolt$activeVanilla = false;
            }
        }

        thunderbolt$selectedVanilla = false;
        thunderbolt$selectedEngine = CraftingPlanningEngines.ALL_FAILED_ID;
        CraftingAlgorithmCalculationStatus.select(
                simRequester, CraftingPlanningEngines.ALL_FAILED_ID);
        this.simulate = true;
        return PlanningFailurePlans.allFailed(output, requestedAmount);
    }

    @Inject(method = "run", at = @At("RETURN"), cancellable = true, remap = false)
    private void thunderbolt$finishCalculation(CallbackInfoReturnable<ICraftingPlan> cir) {
        var result = cir.getReturnValue();
        if (result instanceof CraftingPlan craftingPlan) {
            result = LoopCraftingPlan.wrapIfNeeded(
                    craftingPlan, PlanningMetadataStore.take(craftingPlan));
        }
        cir.setReturnValue(result);
        double wallMs = TimeUnit.NANOSECONDS.toMicros(Math.max(
                0L, System.nanoTime() - thunderbolt$calculationStartedNanos)) / 1_000.0D;
        ThunderboltCore.LOGGER.info(
                "[Thunderbolt Core][crafting-planner] finished: output={} requested={} wallMs={} "
                        + "selected={} attempts={} handled={} declinedEngines={} result={}",
                output, requestedAmount, wallMs,
                thunderbolt$selectedVanilla ? "vanilla" : thunderbolt$selectedEngine,
                thunderbolt$attempts.get(), thunderbolt$handledAttempts.get(),
                thunderbolt$declinedEngines.get(),
                result == null ? "null" : result.getClass().getSimpleName());
        thunderbolt$activeVanilla = false;
        thunderbolt$request = null;
        thunderbolt$candidates = List.of(CapturedPlanningChoice.vanilla());
    }

    @Inject(method = "runCraftAttempt", at = @At("HEAD"), cancellable = true, remap = false)
    private void thunderbolt$planAttempt(
            boolean simulate, long amount, CallbackInfoReturnable<CraftingPlan> cir) {
        thunderbolt$attempts.incrementAndGet();
        if (thunderbolt$activeVanilla) {
            return;
        }
        throw new PlanningCandidateDeclinedException();
    }

    @Unique
    private ICraftingPlan thunderbolt$runVanillaCandidate(
            CraftingCalculation instance,
            Operation<ICraftingPlan> original,
            PlanningAttemptContext context) {
        context.report(PlanningDiagnosticSnapshot.phase("vanilla"));
        context.checkpoint();
        return original.call(instance);
    }

    @Unique
    private ICraftingPlan thunderbolt$runEngineCandidate(
            com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngine engine,
            PlanningRequest request,
            @Nullable Object capturedInput,
            PlanningAttemptContext context) {
        context.report(PlanningDiagnosticSnapshot.phase("creating_session"));
        context.checkpoint();
        var session = engine.createSession(request, capturedInput, context);
        if (session == null) {
            throw new PlanningCandidateDeclinedException();
        }

        try (session) {
            PlanningAttempt first = thunderbolt$probe(session, context, false, requestedAmount);
            CraftingPlan result = first.plan();
            CraftingPlan fullSimulationFallback = first.simulationFallback();
            if (result == null && strategy == CalculationStrategy.CRAFT_LESS
                    && !thunderbolt$budgetExpired(context)) {
                long crafted = 0L;
                CraftingPlan smaller = null;
                for (long bit = Long.highestOneBit(requestedAmount); bit > 0L; bit /= 2L) {
                    long amount = crafted + bit;
                    if (amount < requestedAmount) {
                        var attempt = thunderbolt$probe(session, context, false, amount);
                        if (attempt.plan() != null) {
                            crafted = amount;
                            smaller = attempt.plan();
                            if (thunderbolt$budgetExpired(context)) {
                                break;
                            }
                        }
                    }
                }
                result = smaller;
            }
            if (result == null) {
                result = fullSimulationFallback != null
                        ? fullSimulationFallback
                        : thunderbolt$probe(session, context, true, requestedAmount).plan();
            }
            if (result == null) {
                throw new PlanningCandidateDeclinedException();
            }

            context.report(PlanningDiagnosticSnapshot.phase("finishing"));
            ICraftingPlan finished = session.finish(result, context);
            if (finished == null) {
                throw new PlanningCandidateDeclinedException();
            }
            return finished;
        }
    }

    @Unique
    private PlanningAttempt thunderbolt$probe(
            PlanningEngineSession session,
            PlanningAttemptContext context,
            boolean simulate,
            long amount) {
        thunderbolt$attempts.incrementAndGet();
        PlanningAttempt attempt = session.attempt(amount, simulate, context);
        if (attempt.status() == PlanningAttempt.Status.DECLINE) {
            throw new PlanningCandidateDeclinedException();
        }
        thunderbolt$handledAttempts.incrementAndGet();
        if (attempt.plan() == null && attempt.simulationFallback() == null) {
            if (thunderbolt$budgetExpired(context)) {
                throw new PlanningCandidateDeclinedException();
            }
            try {
                context.checkpoint();
            } catch (PlanningExitException exit) {
                throw new PlanningCandidateDeclinedException();
            }
        }
        return attempt;
    }

    @Unique
    private static void thunderbolt$discardCandidatePlan(ICraftingPlan plan) {
        if (plan instanceof CraftingPlan craftingPlan) {
            PlanningMetadataStore.take(craftingPlan);
        }
    }

    @Unique
    private static boolean thunderbolt$budgetExpired(PlanningAttemptContext context) {
        long deadline = context.deadlineNanos();
        return deadline != Long.MAX_VALUE && System.nanoTime() - deadline >= 0L;
    }

    @Unique
    private void thunderbolt$yieldToAe2Scheduler() throws InterruptedException {
        for (int i = 0; i <= 100; i++) {
            handlePausing();
        }
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T thunderbolt$rethrow(Throwable failure) throws E {
        throw (E) failure;
    }
}
