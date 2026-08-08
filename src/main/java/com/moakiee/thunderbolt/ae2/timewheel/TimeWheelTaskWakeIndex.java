package com.moakiee.thunderbolt.ae2.timewheel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TimeWheelTaskWakeIndex<T> {
   private final Map<Object, Set<T>> tasksByKey = new HashMap<>();
   private final Map<T, Set<Object>> keysByTask = new IdentityHashMap<>();

   boolean park(T task, Iterable<?> keys) {
      this.unpark(task);
      HashSet<Object> taskKeys = new HashSet<>();

      for (Object key : keys) {
         if (key != null) {
            taskKeys.add(key);
         }
      }

      if (taskKeys.isEmpty()) {
         return false;
      } else {
         for (Object keyx : taskKeys) {
            this.tasksByKey.computeIfAbsent(keyx, ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(task);
         }

         this.keysByTask.put(task, taskKeys);
         return true;
      }
   }

   List<T> wake(Object key) {
      Set<T> tasks = this.tasksByKey.remove(key);
      if (tasks != null && !tasks.isEmpty()) {
         ArrayList<T> result = new ArrayList<>(tasks);

         for (T task : result) {
            this.unpark(task);
         }

         return result;
      } else {
         return List.of();
      }
   }

   void unpark(T task) {
      Set<Object> keys = this.keysByTask.remove(task);
      if (keys != null && !keys.isEmpty()) {
         for (Object key : keys) {
            Set<T> tasks = this.tasksByKey.get(key);
            if (tasks != null) {
               tasks.remove(task);
               if (tasks.isEmpty()) {
                  this.tasksByKey.remove(key);
               }
            }
         }
      }
   }

   void clear() {
      this.tasksByKey.clear();
      this.keysByTask.clear();
   }

   boolean isEmpty() {
      return this.keysByTask.isEmpty();
   }
}
