package com.moakiee.thunderbolt.ae2.channel;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ChannelProviderRegistry {
   private static final List<Class<?>> CONTROLLER_CLASSES = new CopyOnWriteArrayList<>();

   private ChannelProviderRegistry() {
   }

   public static void registerController(Class<?> controllerClass) {
      if (controllerClass != null && !CONTROLLER_CLASSES.contains(controllerClass)) {
         CONTROLLER_CLASSES.add(controllerClass);
      }
   }

   public static boolean isChannelProvider(Object owner) {
      if (owner == null) {
         return false;
      } else {
         for (Class<?> controllerClass : CONTROLLER_CLASSES) {
            if (controllerClass.isInstance(owner)) {
               return true;
            }
         }

         return false;
      }
   }

   public static boolean isChannelProviderClass(Class<?> ownerClass) {
      if (ownerClass == null) {
         return false;
      } else {
         for (Class<?> controllerClass : CONTROLLER_CLASSES) {
            if (controllerClass.isAssignableFrom(ownerClass)) {
               return true;
            }
         }

         return false;
      }
   }
}
