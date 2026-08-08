package com.moakiee.thunderbolt.ae2.batch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetails.IInput;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import com.moakiee.thunderbolt.ae2.api.crafting.BatchDispatchContext;
import com.moakiee.thunderbolt.ae2.util.MixinReflectionSupport;
import it.unimi.dsi.fastutil.objects.Object2LongMap.Entry;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public final class NeoEcoPatternBusBatchBridge {
   @Nullable
   private static final Class<?> BUS_CLASS = MixinReflectionSupport.findClassSafe(
      "cn.dancingsnow.neoecoae.blocks.entity.crafting.ECOCraftingPatternBusBlockEntity"
   );
   @Nullable
   private static final Class<?> EXECUTION_CLASS = MixinReflectionSupport.findClassSafe(
      "cn.dancingsnow.neoecoae.impl.crafting.fastpath.ECOExtractedPatternExecution"
   );
   @Nullable
   private static final Class<?> REQUEST_CLASS = MixinReflectionSupport.findClassSafe("cn.dancingsnow.neoecoae.impl.crafting.fastpath.ECOBatchCraftingRequest");
   @Nullable
   private static final Class<?> KEY_CLASS = MixinReflectionSupport.findClassSafe("cn.dancingsnow.neoecoae.impl.crafting.fastpath.ECOFastPathKey");
   @Nullable
   private static final Class<?> OFFER_CLASS = MixinReflectionSupport.findClassSafe(
      "cn.dancingsnow.neoecoae.blocks.entity.crafting.ECOCraftingPatternBusBlockEntity$BatchFastPathOffer"
   );
   @Nullable
   private static final Method GET_AVAILABLE_THREAD_SLOTS = findMethod(BUS_CLASS, "getAvailableThreadSlots");
   @Nullable
   private static final Method CREATE_EXECUTION = findMethod(
      EXECUTION_CLASS, "create", IPatternDetails.class, KeyCounter[].class, KeyCounter.class, KeyCounter.class, Level.class
   );
   @Nullable
   private static final Method EXECUTION_FAST_PATH_ELIGIBLE = findMethod(EXECUTION_CLASS, "fastPathEligible");
   @Nullable
   private static final Method EXECUTION_KEY = findMethod(EXECUTION_CLASS, "key");
   @Nullable
   private static final Method EXECUTION_INPUTS = findMethod(EXECUTION_CLASS, "inputItems");
   @Nullable
   private static final Method EXECUTION_OUTPUTS = findMethod(EXECUTION_CLASS, "expectedOutputs");
   @Nullable
   private static final Method EXECUTION_REMAINING = findMethod(EXECUTION_CLASS, "expectedContainerItems");
   @Nullable
   private static final Method FIND_BATCH_OFFER = findMethod(BUS_CLASS, "findBatchFastPathOffer", EXECUTION_CLASS, int.class);
   @Nullable
   private static final Method OFFER_MAX_BATCH = findMethod(OFFER_CLASS, "maxBatchSize");
   @Nullable
   private static final Method PUSH_EXECUTION = findMethod(BUS_CLASS, "pushPattern", EXECUTION_CLASS, UUID.class);
   @Nullable
   private static final Method PUSH_BATCH = findMethod(BUS_CLASS, "pushBatch", REQUEST_CLASS, OFFER_CLASS);
   @Nullable
   private static final Constructor<?> REQUEST_CONSTRUCTOR = findConstructor(
      REQUEST_CLASS, IPatternDetails.class, KEY_CLASS, int.class, List.class, List.class, List.class, UUID.class
   );
   private static final boolean AVAILABLE = BUS_CLASS != null
      && EXECUTION_CLASS != null
      && REQUEST_CLASS != null
      && KEY_CLASS != null
      && OFFER_CLASS != null
      && GET_AVAILABLE_THREAD_SLOTS != null
      && CREATE_EXECUTION != null
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

   public static long capacity(Object patternBus, IPatternDetails details) {
      if (AVAILABLE && BUS_CLASS.isInstance(patternBus) && details instanceof IMolecularAssemblerSupportedPattern) {
         return MixinReflectionSupport.invokeMethodSafe(GET_AVAILABLE_THREAD_SLOTS, patternBus, "query NeoECO pattern-bus batch capacity") instanceof Number number
            ? Math.max(0L, number.longValue())
            : 1L;
      } else {
         return 1L;
      }
   }

   public static long pushBatch(Object patternBus, BatchDispatchContext context) {
      long maxCraft = context.maxCraft();
      if (maxCraft <= 0L) {
         return 0L;
      } else if (AVAILABLE && BUS_CLASS.isInstance(patternBus) && context.details() != null && context.oneCopyTemplate() != null && context.level() != null) {
         Object execution = createExecution(context);
         if (execution != null && isTrue(invoke(EXECUTION_FAST_PATH_ELIGIBLE, execution, "check NeoECO fast-path eligibility"))) {
            int requested = (int)Math.min(maxCraft, 2147483647L);
            long accepted = pushVerifiedBatch(patternBus, execution, context, requested);
            if (accepted > 0L) {
               return maxCraft - accepted;
            } else if (!isTrue(invoke(PUSH_EXECUTION, patternBus, "warm NeoECO verified batch cache", execution, context.craftingJobId()))) {
               return maxCraft;
            } else {
               accepted = 1L;
               long remaining = maxCraft - accepted;
               if (remaining > 0L) {
                  int remainingRequest = (int)Math.min(remaining, 2147483647L);
                  accepted += pushVerifiedBatch(patternBus, execution, context, remainingRequest);
               }

               return maxCraft - accepted;
            }
         } else {
            return maxCraft;
         }
      } else {
         return maxCraft;
      }
   }

   @Nullable
   private static Object createExecution(BatchDispatchContext context) {
      KeyCounter outputs = new KeyCounter();

      for (GenericStack output : context.details().getOutputs()) {
         if (output != null && output.what() != null && output.amount() > 0L) {
            outputs.add(output.what(), output.amount());
         }
      }

      KeyCounter remainingItems = new KeyCounter();
      IInput[] inputs = context.details().getInputs();
      KeyCounter[] template = context.oneCopyTemplate();

      for (int slot = 0; slot < inputs.length && slot < template.length; slot++) {
         AEKey consumed = firstKey(template[slot]);
         if (consumed != null) {
            AEKey remaining = inputs[slot].getRemainingKey(consumed);
            if (remaining != null && inputs[slot].getMultiplier() > 0L) {
               remainingItems.add(remaining, inputs[slot].getMultiplier());
            }
         }
      }

      return invoke(CREATE_EXECUTION, null, "create NeoECO extracted pattern execution", context.details(), template, outputs, remainingItems, context.level());
   }

   private static long pushVerifiedBatch(Object patternBus, Object execution, BatchDispatchContext context, int requested) {
      if (requested <= 0) {
         return 0L;
      } else {
         Object offer = invoke(FIND_BATCH_OFFER, patternBus, "find NeoECO verified batch offer", execution, requested);
         if (offer == null) {
            return 0L;
         } else if (invoke(OFFER_MAX_BATCH, offer, "read NeoECO verified batch offer") instanceof Number number) {
            int batchSize = Math.min(requested, Math.max(0, number.intValue()));
            if (batchSize <= 0) {
               return 0L;
            } else {
               Object request = createRequest(context, execution, batchSize);
               if (request == null) {
                  return 0L;
               } else {
                  return isTrue(invoke(PUSH_BATCH, patternBus, "dispatch NeoECO verified pattern batch", request, offer)) ? (long)batchSize : 0L;
               }
            }
         } else {
            return 0L;
         }
      }
   }

   @Nullable
   private static Object createRequest(BatchDispatchContext context, Object execution, int batchSize) {
      Object key = invoke(EXECUTION_KEY, execution, "read NeoECO fast-path key");
      Object inputs = invoke(EXECUTION_INPUTS, execution, "read NeoECO fast-path inputs");
      Object outputs = invoke(EXECUTION_OUTPUTS, execution, "read NeoECO fast-path outputs");
      Object remaining = invoke(EXECUTION_REMAINING, execution, "read NeoECO fast-path remainders");
      if (key != null && inputs instanceof List && outputs instanceof List && remaining instanceof List) {
         try {
            return REQUEST_CONSTRUCTOR.newInstance(context.details(), key, batchSize, inputs, outputs, remaining, context.craftingJobId());
         } catch (RuntimeException | ReflectiveOperationException var8) {
            MixinReflectionSupport.logReflectionFailure("construct NeoECO verified batch request", var8);
            return null;
         }
      } else {
         return null;
      }
   }

   @Nullable
   private static AEKey firstKey(@Nullable KeyCounter counter) {
      if (counter == null) {
         return null;
      } else {
         for (Entry<AEKey> entry : counter) {
            if (entry.getKey() != null && entry.getLongValue() > 0L) {
               return (AEKey)entry.getKey();
            }
         }

         return null;
      }
   }

   private static boolean isTrue(@Nullable Object value) {
      return Boolean.TRUE.equals(value);
   }

   @Nullable
   private static Object invoke(@Nullable Method method, @Nullable Object target, String action, Object... args) {
      return MixinReflectionSupport.invokeMethodSafe(method, target, action, args);
   }

   @Nullable
   private static Method findMethod(@Nullable Class<?> owner, String name, @Nullable Class<?>... parameterTypes) {
      return owner != null && !containsNull(parameterTypes) ? MixinReflectionSupport.findDeclaredMethodSafe(owner, name, parameterTypes) : null;
   }

   @Nullable
   private static Constructor<?> findConstructor(@Nullable Class<?> owner, @Nullable Class<?>... parameterTypes) {
      if (owner != null && !containsNull(parameterTypes)) {
         try {
            Constructor<?> constructor = owner.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return constructor;
         } catch (RuntimeException | ReflectiveOperationException var3) {
            return null;
         }
      } else {
         return null;
      }
   }

   private static boolean containsNull(Class<?>[] values) {
      for (Class<?> value : values) {
         if (value == null) {
            return true;
         }
      }

      return false;
   }
}
