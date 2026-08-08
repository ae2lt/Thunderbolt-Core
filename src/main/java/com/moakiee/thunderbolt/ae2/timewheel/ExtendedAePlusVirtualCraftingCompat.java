package com.moakiee.thunderbolt.ae2.timewheel;

import java.lang.reflect.Method;

final class ExtendedAePlusVirtualCraftingCompat {
   static final String ENABLED_METHOD = "eap$compatIsVirtualCraftingEnabled";
   private static final ExtendedAePlusVirtualCraftingCompat.StateReader NO_STATE = ignored -> false;
   private static final ClassValue<ExtendedAePlusVirtualCraftingCompat.StateReader> STATE_READERS = new ClassValue<ExtendedAePlusVirtualCraftingCompat.StateReader>() {
      protected ExtendedAePlusVirtualCraftingCompat.StateReader computeValue(Class<?> type) {
         try {
            Method method = type.getMethod("eap$compatIsVirtualCraftingEnabled");
            return method.getParameterCount() == 0 && method.getReturnType() == boolean.class ? provider -> {
               try {
                  return (Boolean)method.invoke(provider);
               } catch (RuntimeException | ReflectiveOperationException var3x) {
                  return false;
               }
            } : ExtendedAePlusVirtualCraftingCompat.NO_STATE;
         } catch (SecurityException | NoSuchMethodException var3) {
            return ExtendedAePlusVirtualCraftingCompat.NO_STATE;
         }
      }
   };
   private static final ThreadLocal<Integer> TIME_WHEEL_PUSH_DEPTH = ThreadLocal.withInitial(() -> 0);

   private ExtendedAePlusVirtualCraftingCompat() {
   }

   static boolean isVirtualCraftingEnabled(Object provider) {
      return provider != null && STATE_READERS.get(provider.getClass()).read(provider);
   }

   static ExtendedAePlusVirtualCraftingCompat.DispatchScope enterTimeWheelProviderPush() {
      TIME_WHEEL_PUSH_DEPTH.set(TIME_WHEEL_PUSH_DEPTH.get() + 1);
      return new ExtendedAePlusVirtualCraftingCompat.DispatchScope();
   }

   public static boolean isTimeWheelProviderPushActive() {
      return TIME_WHEEL_PUSH_DEPTH.get() > 0;
   }

   static boolean shouldRequestCompletion(
      boolean virtualCraftingEnabled, boolean sameActiveJob, boolean closedLoopJob, boolean softCancelling, boolean tasksEmpty
   ) {
      return virtualCraftingEnabled && sameActiveJob && !closedLoopJob && !softCancelling && tasksEmpty;
   }

   static final class DispatchScope implements AutoCloseable {
      private boolean closed;

      @Override
      public void close() {
         if (!this.closed) {
            this.closed = true;
            int depth = ExtendedAePlusVirtualCraftingCompat.TIME_WHEEL_PUSH_DEPTH.get() - 1;
            if (depth <= 0) {
               ExtendedAePlusVirtualCraftingCompat.TIME_WHEEL_PUSH_DEPTH.remove();
            } else {
               ExtendedAePlusVirtualCraftingCompat.TIME_WHEEL_PUSH_DEPTH.set(depth);
            }
         }
      }
   }

   @FunctionalInterface
   private interface StateReader {
      boolean read(Object var1);
   }
}
