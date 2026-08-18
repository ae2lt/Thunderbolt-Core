package com.moakiee.thunderbolt.ae2.batch;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;

import com.moakiee.thunderbolt.ae2.api.crafting.BatchDispatchContext;
import com.moakiee.thunderbolt.ae2.util.MixinReflectionSupport;

/**
 * Reflection bridge to NeoECO's optional verified batch fast path.
 * <p>
 * Reflection targets are locked by {@code NeoEcoBinaryShapeTest}. Both the legacy execution
 * factory and the Forge 1.20.1 20.x compiled-pattern factory are supported.
 */
public final class NeoEcoPatternBusBatchBridge {
    private static final @Nullable Class<?> BUS_CLASS = MixinReflectionSupport.findClassSafe(
            "cn.dancingsnow.neoecoae.blocks.entity.crafting.ECOCraftingPatternBusBlockEntity");
    private static final @Nullable Class<?> EXECUTION_CLASS = MixinReflectionSupport.findClassSafe(
            "cn.dancingsnow.neoecoae.impl.crafting.fastpath.ECOExtractedPatternExecution");
    private static final @Nullable Class<?> REQUEST_CLASS = MixinReflectionSupport.findClassSafe(
            "cn.dancingsnow.neoecoae.impl.crafting.fastpath.ECOBatchCraftingRequest");
    private static final @Nullable Class<?> KEY_CLASS = MixinReflectionSupport.findClassSafe(
            "cn.dancingsnow.neoecoae.impl.crafting.fastpath.ECOFastPathKey");
    private static final @Nullable Class<?> OFFER_CLASS = MixinReflectionSupport.findClassSafe(
            "cn.dancingsnow.neoecoae.blocks.entity.crafting."
                    + "ECOCraftingPatternBusBlockEntity$BatchFastPathOffer");
    private static final @Nullable Class<?> COMPILED_PATTERN_CLASS = MixinReflectionSupport.findClassSafe(
            "cn.dancingsnow.neoecoae.impl.crafting.fastpath.ECOCompiledFastPathPattern");
    private static final @Nullable Class<?> PATTERN_METADATA_CLASS = MixinReflectionSupport.findClassSafe(
            "cn.dancingsnow.neoecoae.impl.crafting.fastpath.ECOFastPathPatternMetadata");
    private static final @Nullable Class<?> FAST_PATH_ELIGIBILITY_CLASS = MixinReflectionSupport.findClassSafe(
            "cn.dancingsnow.neoecoae.api.me.ECOFastPathEligibility");

    private static final @Nullable Method GET_AVAILABLE_THREAD_SLOTS = findMethod(
            BUS_CLASS, "getAvailableThreadSlots");
    private static final @Nullable Method CREATE_LEGACY_EXECUTION = findMethod(
            EXECUTION_CLASS,
            "create",
            IPatternDetails.class,
            KeyCounter[].class,
            KeyCounter.class,
            KeyCounter.class,
            Level.class);
    private static final @Nullable Method COMPILE_PATTERN = findMethod(
            COMPILED_PATTERN_CLASS, "compile", IPatternDetails.class);
    private static final @Nullable Method CAN_BUILD_FAST_PATH = findMethod(
            COMPILED_PATTERN_CLASS, "canBuildFastPath", List.class);
    private static final @Nullable Method CREATE_PATTERN_METADATA = findMethod(
            PATTERN_METADATA_CLASS,
            "create",
            COMPILED_PATTERN_CLASS,
            KeyCounter[].class,
            Level.class);
    private static final @Nullable Method CREATE_FORGE_1201_EXECUTION = findMethod(
            EXECUTION_CLASS,
            "create",
            IPatternDetails.class,
            COMPILED_PATTERN_CLASS,
            PATTERN_METADATA_CLASS,
            KeyCounter[].class,
            List.class,
            boolean.class,
            Level.class);
    private static final @Nullable Method IS_FAST_PATH_GLOBALLY_ENABLED = findMethod(
            FAST_PATH_ELIGIBILITY_CLASS, "isGloballyEnabled");
    private static final @Nullable Method EXECUTION_FAST_PATH_ELIGIBLE = findMethod(
            EXECUTION_CLASS, "fastPathEligible");
    private static final @Nullable Method EXECUTION_KEY = findMethod(EXECUTION_CLASS, "key");
    private static final @Nullable Method EXECUTION_INPUTS = findMethod(EXECUTION_CLASS, "inputItems");
    private static final @Nullable Method EXECUTION_OUTPUTS = findMethod(EXECUTION_CLASS, "expectedOutputs");
    private static final @Nullable Method EXECUTION_REMAINING = findMethod(
            EXECUTION_CLASS, "expectedContainerItems");
    private static final @Nullable Method FIND_BATCH_OFFER = findMethod(
            BUS_CLASS, "findBatchFastPathOffer", EXECUTION_CLASS, int.class);
    private static final @Nullable Method OFFER_MAX_BATCH = findMethod(OFFER_CLASS, "maxBatchSize");
    private static final @Nullable Method PUSH_EXECUTION = findMethod(
            BUS_CLASS, "pushPattern", EXECUTION_CLASS, UUID.class);
    private static final @Nullable Method PUSH_BATCH = findMethod(
            BUS_CLASS, "pushBatch", REQUEST_CLASS, OFFER_CLASS);
    private static final @Nullable Constructor<?> REQUEST_CONSTRUCTOR = findConstructor(
            REQUEST_CLASS,
            IPatternDetails.class,
            KEY_CLASS,
            int.class,
            List.class,
            List.class,
            List.class,
            UUID.class);

    private static final boolean LEGACY_EXECUTION_AVAILABLE = CREATE_LEGACY_EXECUTION != null;
    private static final boolean FORGE_1201_EXECUTION_AVAILABLE = COMPILED_PATTERN_CLASS != null
            && PATTERN_METADATA_CLASS != null
            && FAST_PATH_ELIGIBILITY_CLASS != null
            && COMPILE_PATTERN != null
            && CAN_BUILD_FAST_PATH != null
            && CREATE_PATTERN_METADATA != null
            && CREATE_FORGE_1201_EXECUTION != null
            && IS_FAST_PATH_GLOBALLY_ENABLED != null;

    private static final boolean AVAILABLE = BUS_CLASS != null
            && EXECUTION_CLASS != null
            && REQUEST_CLASS != null
            && KEY_CLASS != null
            && OFFER_CLASS != null
            && GET_AVAILABLE_THREAD_SLOTS != null
            && (LEGACY_EXECUTION_AVAILABLE || FORGE_1201_EXECUTION_AVAILABLE)
            && EXECUTION_FAST_PATH_ELIGIBLE != null
            && EXECUTION_KEY != null
            && EXECUTION_INPUTS != null
            && EXECUTION_OUTPUTS != null
            && EXECUTION_REMAINING != null
            && FIND_BATCH_OFFER != null
            && OFFER_MAX_BATCH != null
            && PUSH_EXECUTION != null
            && PUSH_BATCH != null
            && REQUEST_CONSTRUCTOR != null;

    private NeoEcoPatternBusBatchBridge() {
    }

    /** Old NeoECO versions have no verified batch API and remain ordinary single-copy providers. */
    public static long capacity(Object patternBus, IPatternDetails details) {
        if (!AVAILABLE
                || !BUS_CLASS.isInstance(patternBus)
                || !(details instanceof IMolecularAssemblerSupportedPattern)) {
            return 1L;
        }
        Object result = MixinReflectionSupport.invokeMethodSafe(
                GET_AVAILABLE_THREAD_SLOTS,
                patternBus,
                "query NeoECO pattern-bus batch capacity");
        return result instanceof Number number
                ? Math.max(0L, number.longValue())
                : 1L;
    }

    public static long pushBatch(Object patternBus, BatchDispatchContext context) {
        long maxCraft = context.maxCraft();
        if (maxCraft <= 0L) {
            return 0L;
        }
        if (!AVAILABLE
                || !BUS_CLASS.isInstance(patternBus)
                || context.details() == null
                || context.oneCopyTemplate() == null
                || context.level() == null) {
            return maxCraft;
        }

        Object execution = createExecution(context);
        if (execution == null || !isTrue(invoke(
                EXECUTION_FAST_PATH_ELIGIBLE,
                execution,
                "check NeoECO fast-path eligibility"))) {
            // Let the owning CPU immediately resume NeoECO/AE2's ordinary path. Do not claim one
            // copy here: that would reduce a non-fast-path recipe to one dispatch per tick.
            return maxCraft;
        }

        int requested = (int) Math.min(maxCraft, Integer.MAX_VALUE);
        long accepted = pushVerifiedBatch(patternBus, execution, context, requested);
        if (accepted > 0L) {
            return maxCraft - accepted;
        }

        // A new verified shape has no cache entry yet. Execute exactly one copy through NeoECO's
        // validating path; it records either a positive or negative cache result synchronously.
        if (!isTrue(invoke(
                PUSH_EXECUTION,
                patternBus,
                "warm NeoECO verified batch cache",
                execution,
                context.craftingJobId()))) {
            return maxCraft;
        }
        accepted = 1L;

        long remaining = maxCraft - accepted;
        if (remaining > 0L) {
            int remainingRequest = (int) Math.min(remaining, Integer.MAX_VALUE);
            accepted += pushVerifiedBatch(
                    patternBus, execution, context, remainingRequest);
        }
        return maxCraft - accepted;
    }

    @Nullable
    private static Object createExecution(BatchDispatchContext context) {
        var outputs = new KeyCounter();
        for (var output : context.details().getOutputs()) {
            if (output != null && output.what() != null && output.amount() > 0L) {
                outputs.add(output.what(), output.amount());
            }
        }

        var remainingItems = new KeyCounter();
        var inputs = context.details().getInputs();
        var template = context.oneCopyTemplate();
        for (int slot = 0; slot < inputs.length && slot < template.length; slot++) {
            AEKey consumed = firstKey(template[slot]);
            if (consumed == null) {
                continue;
            }
            AEKey remaining = inputs[slot].getRemainingKey(consumed);
            if (remaining != null && inputs[slot].getMultiplier() > 0L) {
                remainingItems.add(remaining, inputs[slot].getMultiplier());
            }
        }

        if (FORGE_1201_EXECUTION_AVAILABLE) {
            var remainingStacks = copyCounter(remainingItems);
            Object compiledPattern = invoke(
                    COMPILE_PATTERN,
                    null,
                    "compile NeoECO fast-path pattern",
                    context.details());
            if (compiledPattern == null) {
                return null;
            }
            boolean globallyEnabled = isTrue(invoke(
                    IS_FAST_PATH_GLOBALLY_ENABLED,
                    null,
                    "query NeoECO fast-path configuration"));
            boolean canBuildFastPath = globallyEnabled && isTrue(invoke(
                    CAN_BUILD_FAST_PATH,
                    compiledPattern,
                    "validate NeoECO fast-path pattern",
                    remainingStacks));
            Object metadata = canBuildFastPath
                    ? invoke(
                            CREATE_PATTERN_METADATA,
                            null,
                            "create NeoECO fast-path metadata",
                            compiledPattern,
                            template,
                            context.level())
                    : null;
            return invoke(
                    CREATE_FORGE_1201_EXECUTION,
                    null,
                    "create NeoECO Forge 1.20.1 extracted pattern execution",
                    context.details(),
                    compiledPattern,
                    metadata,
                    template,
                    remainingStacks,
                    canBuildFastPath,
                    context.level());
        }

        return invoke(
                CREATE_LEGACY_EXECUTION,
                null,
                "create legacy NeoECO extracted pattern execution",
                context.details(),
                template,
                outputs,
                remainingItems,
                context.level());
    }

    private static List<GenericStack> copyCounter(KeyCounter counter) {
        var result = new ArrayList<GenericStack>();
        for (var entry : counter) {
            if (entry.getKey() != null && entry.getLongValue() > 0L) {
                result.add(new GenericStack(entry.getKey(), entry.getLongValue()));
            }
        }
        return List.copyOf(result);
    }

    private static long pushVerifiedBatch(
            Object patternBus,
            Object execution,
            BatchDispatchContext context,
            int requested) {
        if (requested <= 0) {
            return 0L;
        }
        Object offer = invoke(
                FIND_BATCH_OFFER,
                patternBus,
                "find NeoECO verified batch offer",
                execution,
                requested);
        if (offer == null) {
            return 0L;
        }
        Object rawOfferLimit = invoke(
                OFFER_MAX_BATCH,
                offer,
                "read NeoECO verified batch offer");
        if (!(rawOfferLimit instanceof Number number)) {
            return 0L;
        }
        int batchSize = Math.min(requested, Math.max(0, number.intValue()));
        if (batchSize <= 0) {
            return 0L;
        }

        Object request = createRequest(context, execution, batchSize);
        if (request == null) {
            return 0L;
        }
        return isTrue(invoke(
                PUSH_BATCH,
                patternBus,
                "dispatch NeoECO verified pattern batch",
                request,
                offer))
                ? batchSize
                : 0L;
    }

    @Nullable
    private static Object createRequest(
            BatchDispatchContext context,
            Object execution,
            int batchSize) {
        Object key = invoke(EXECUTION_KEY, execution, "read NeoECO fast-path key");
        Object inputs = invoke(EXECUTION_INPUTS, execution, "read NeoECO fast-path inputs");
        Object outputs = invoke(EXECUTION_OUTPUTS, execution, "read NeoECO fast-path outputs");
        Object remaining = invoke(EXECUTION_REMAINING, execution, "read NeoECO fast-path remainders");
        if (key == null
                || !(inputs instanceof List<?>)
                || !(outputs instanceof List<?>)
                || !(remaining instanceof List<?>)) {
            return null;
        }
        try {
            return REQUEST_CONSTRUCTOR.newInstance(
                    context.details(),
                    key,
                    batchSize,
                    inputs,
                    outputs,
                    remaining,
                    context.craftingJobId());
        } catch (ReflectiveOperationException | RuntimeException e) {
            MixinReflectionSupport.logReflectionFailure(
                    "construct NeoECO verified batch request", e);
            return null;
        }
    }

    @Nullable
    private static AEKey firstKey(@Nullable KeyCounter counter) {
        if (counter == null) {
            return null;
        }
        for (var entry : counter) {
            if (entry.getKey() != null && entry.getLongValue() > 0L) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static boolean isTrue(@Nullable Object value) {
        return Boolean.TRUE.equals(value);
    }

    @Nullable
    private static Object invoke(
            @Nullable Method method,
            @Nullable Object target,
            String action,
            Object... args) {
        return MixinReflectionSupport.invokeMethodSafe(method, target, action, args);
    }

    @Nullable
    private static Method findMethod(
            @Nullable Class<?> owner,
            String name,
            @Nullable Class<?>... parameterTypes) {
        if (owner == null || containsNull(parameterTypes)) {
            return null;
        }
        return MixinReflectionSupport.findDeclaredMethodSafe(owner, name, parameterTypes);
    }

    @Nullable
    private static Constructor<?> findConstructor(
            @Nullable Class<?> owner,
            @Nullable Class<?>... parameterTypes) {
        if (owner == null || containsNull(parameterTypes)) {
            return null;
        }
        try {
            var constructor = owner.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static boolean containsNull(Class<?>[] values) {
        for (var value : values) {
            if (value == null) {
                return true;
            }
        }
        return false;
    }
}
