package com.moakiee.thunderbolt.ae2.util;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

public final class MixinReflectionSupport {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final Set<String> LOGGED_FAILURES = Collections.synchronizedSet(new HashSet<>());

   private MixinReflectionSupport() {
   }

   @Nullable
   public static Class<?> findClassSafe(String fqn) {
      try {
         return Class.forName(fqn);
      } catch (Exception var2) {
         return null;
      }
   }

   @Nullable
   public static Field findDeclaredFieldSafe(@Nullable Class<?> owner, String name) {
      if (owner == null) {
         return null;
      } else {
         try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
         } catch (Exception var3) {
            return null;
         }
      }
   }

   @Nullable
   public static Method findDeclaredMethodSafe(@Nullable Class<?> owner, String name, Class<?>... parameterTypes) {
      if (owner == null) {
         return null;
      } else {
         try {
            Method method = owner.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
         } catch (Exception var4) {
            return null;
         }
      }
   }

   @Nullable
   public static Object getFieldValueSafe(@Nullable Field field, Object target) {
      if (field == null) {
         return null;
      } else {
         try {
            return field.get(target);
         } catch (ReflectiveOperationException var3) {
            logReflectionFailure("read reflective field", var3);
            return null;
         }
      }
   }

   public static long getLongFieldSafe(@Nullable Field field, Object target, long fallback) {
      if (field == null) {
         return fallback;
      } else {
         try {
            return field.getLong(target);
         } catch (ReflectiveOperationException var5) {
            logReflectionFailure("read reflective long field", var5);
            return fallback;
         }
      }
   }

   public static void setLongFieldSafe(@Nullable Field field, Object target, long value, String action) {
      if (field != null) {
         try {
            field.setLong(target, value);
         } catch (ReflectiveOperationException var6) {
            logReflectionFailure(action, var6);
         }
      }
   }

   @Nullable
   public static Object invokeMethodSafe(@Nullable Method method, Object target, String action, Object... args) {
      if (method == null) {
         return null;
      } else {
         try {
            return method.invoke(target, args);
         } catch (ReflectiveOperationException var5) {
            logReflectionFailure(action, var5);
            return null;
         }
      }
   }

   public static void logReflectionFailure(String action, Throwable exception) {
      if (LOGGED_FAILURES.add(action)) {
         LOGGER.warn("AE2LT reflection failed while trying to {}.", action, exception);
      }
   }
}
