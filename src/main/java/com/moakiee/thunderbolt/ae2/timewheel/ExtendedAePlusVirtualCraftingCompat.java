package com.moakiee.thunderbolt.ae2.timewheel;

import java.lang.reflect.Method;

/**
 * Optional bridge for ExtendedAE Plus' virtual crafting card.
 *
 * <p>ExtendedAE Plus exposes the card state through a public method mixed into
 * {@code PatternProviderLogic}, but does not publish a stable API dependency. Looking the method up
 * by shape keeps Thunderbolt loadable when the addon is absent while still supporting the current
 * bridge implementation. The lookup result is cached per provider class, so ordinary dispatch does
 * not repeatedly pay reflective lookup cost.
 */
final class ExtendedAePlusVirtualCraftingCompat {
    static final String ENABLED_METHOD = "eap$compatIsVirtualCraftingEnabled";

    private static final StateReader NO_STATE = ignored -> false;
    private static final ClassValue<StateReader> STATE_READERS = new ClassValue<>() {
        @Override
        protected StateReader computeValue(Class<?> type) {
            try {
                Method method = type.getMethod(ENABLED_METHOD);
                if (method.getParameterCount() != 0 || method.getReturnType() != boolean.class) {
                    return NO_STATE;
                }
                return provider -> {
                    try {
                        return (boolean) method.invoke(provider);
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        return false;
                    }
                };
            } catch (NoSuchMethodException | SecurityException ignored) {
                return NO_STATE;
            }
        }
    };

    private static final ThreadLocal<Integer> TIME_WHEEL_PUSH_DEPTH =
            ThreadLocal.withInitial(() -> 0);

    private ExtendedAePlusVirtualCraftingCompat() {
    }

    static boolean isVirtualCraftingEnabled(Object provider) {
        return provider != null && STATE_READERS.get(provider.getClass()).read(provider);
    }

    /**
     * Marks the synchronous provider call as owned by a time-wheel CPU.
     *
     * <p>ExtendedAE Plus normally scans every CPU on the grid from inside the provider callback.
     * Its optional suppression mixin uses this scope to avoid completing an unrelated vanilla or
     * AdvancedAE job that happens to use the same pattern.
     */
    static DispatchScope enterTimeWheelProviderPush() {
        TIME_WHEEL_PUSH_DEPTH.set(TIME_WHEEL_PUSH_DEPTH.get() + 1);
        return new DispatchScope();
    }

    public static boolean isTimeWheelProviderPushActive() {
        return TIME_WHEEL_PUSH_DEPTH.get() > 0;
    }

    static boolean shouldRequestCompletion(
            boolean virtualCraftingEnabled,
            boolean sameActiveJob,
            boolean closedLoopJob,
            boolean softCancelling,
            boolean tasksEmpty) {
        return virtualCraftingEnabled
                && sameActiveJob
                && !closedLoopJob
                && !softCancelling
                && tasksEmpty;
    }

    @FunctionalInterface
    private interface StateReader {
        boolean read(Object provider);
    }

    static final class DispatchScope implements AutoCloseable {
        private boolean closed;

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            int depth = TIME_WHEEL_PUSH_DEPTH.get() - 1;
            if (depth <= 0) {
                TIME_WHEEL_PUSH_DEPTH.remove();
            } else {
                TIME_WHEEL_PUSH_DEPTH.set(depth);
            }
        }
    }
}
