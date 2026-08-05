package com.moakiee.thunderbolt.ae2.channel;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of "channel provider source" controllers for the channel max-flow subsystem.
 *
 * <p>An overloaded controller acts as a channel <em>source</em> in the flow network (it injects
 * {@code channelsPerController} of supply). Rather than coupling the content-agnostic algorithm to a
 * concrete block-entity type or forcing it to implement a lib marker interface, the host mod
 * <b>registers its controller class</b> here once during setup. The grid helpers/mixins then test
 * ownership against the registered classes.
 *
 * <p>Both instance and class tests are supported because AE2 keys its machine-node registry by the
 * exact owner {@code Class}, so {@code GridGetMachineNodesMixin} needs a class-level predicate while
 * the flow solver needs instance-level checks.
 */
public final class ChannelProviderRegistry {

    private static final List<Class<?>> CONTROLLER_CLASSES = new CopyOnWriteArrayList<>();

    private ChannelProviderRegistry() {
    }

    /**
     * Registers a controller class (and, transitively, its subclasses) as an overloaded channel
     * provider source. Idempotent. Call once from the host mod's setup.
     */
    public static void registerController(Class<?> controllerClass) {
        if (controllerClass != null && !CONTROLLER_CLASSES.contains(controllerClass)) {
            CONTROLLER_CLASSES.add(controllerClass);
        }
    }

    /** @return true if {@code owner} is an instance of any registered channel-provider controller. */
    public static boolean isChannelProvider(Object owner) {
        if (owner == null) {
            return false;
        }
        for (var controllerClass : CONTROLLER_CLASSES) {
            if (controllerClass.isInstance(owner)) {
                return true;
            }
        }
        return false;
    }

    /** @return true if {@code ownerClass} is (a subtype of) any registered channel-provider controller. */
    public static boolean isChannelProviderClass(Class<?> ownerClass) {
        if (ownerClass == null) {
            return false;
        }
        for (var controllerClass : CONTROLLER_CLASSES) {
            if (controllerClass.isAssignableFrom(ownerClass)) {
                return true;
            }
        }
        return false;
    }
}
