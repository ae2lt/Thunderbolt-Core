package com.moakiee.thunderbolt.api.wireless;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public final class WirelessConnectionBatchEdit {
   private WirelessConnectionBatchEdit() {
   }

   public static <T, C, D, F> WirelessConnectionBatchEdit.Plan<T> planSingleFacePerTarget(
      Iterable<T> targets, D dimension, Iterable<C> connections, F face, Function<C, D> dimensionGetter, Function<C, T> posGetter, Function<C, F> faceGetter
   ) {
      List<T> targetList = copyTargets(targets);
      if (targetList.isEmpty()) {
         return emptyPlan();
      } else {
         boolean allSelected = true;

         for (T target : targetList) {
            C existing = findTargetConnection(connections, dimension, target, dimensionGetter, posGetter);
            if (existing == null || !Objects.equals(faceGetter.apply(existing), face)) {
               allSelected = false;
               break;
            }
         }

         if (allSelected) {
            return new WirelessConnectionBatchEdit.Plan<>(true, targetList, List.of(), List.of());
         } else {
            ArrayList<T> update = new ArrayList<>();
            ArrayList<T> connect = new ArrayList<>();

            for (T targetx : targetList) {
               C existing = findTargetConnection(connections, dimension, targetx, dimensionGetter, posGetter);
               if (existing == null) {
                  connect.add(targetx);
               } else if (!Objects.equals(faceGetter.apply(existing), face)) {
                  update.add(targetx);
               }
            }

            return new WirelessConnectionBatchEdit.Plan<>(false, List.of(), List.copyOf(update), List.copyOf(connect));
         }
      }
   }

   public static <T, C, D, F> WirelessConnectionBatchEdit.Plan<T> planMultiFacePerTarget(
      Iterable<T> targets, D dimension, Iterable<C> connections, F face, Function<C, D> dimensionGetter, Function<C, T> posGetter, Function<C, F> faceGetter
   ) {
      List<T> targetList = copyTargets(targets);
      if (targetList.isEmpty()) {
         return emptyPlan();
      } else {
         boolean allSelected = true;

         for (T target : targetList) {
            if (!hasEndpoint(connections, dimension, target, face, dimensionGetter, posGetter, faceGetter)) {
               allSelected = false;
               break;
            }
         }

         if (allSelected) {
            return new WirelessConnectionBatchEdit.Plan<>(true, targetList, List.of(), List.of());
         } else {
            ArrayList<T> connect = new ArrayList<>();

            for (T targetx : targetList) {
               if (!hasEndpoint(connections, dimension, targetx, face, dimensionGetter, posGetter, faceGetter)) {
                  connect.add(targetx);
               }
            }

            return new WirelessConnectionBatchEdit.Plan<>(false, List.of(), List.of(), List.copyOf(connect));
         }
      }
   }

   private static <T> List<T> copyTargets(Iterable<T> targets) {
      ArrayList<T> result = new ArrayList<>();

      for (T target : targets) {
         result.add(target);
      }

      return List.copyOf(result);
   }

   private static <T> WirelessConnectionBatchEdit.Plan<T> emptyPlan() {
      return new WirelessConnectionBatchEdit.Plan<>(false, List.of(), List.of(), List.of());
   }

   private static <T, C, D> C findTargetConnection(Iterable<C> connections, D dimension, T target, Function<C, D> dimensionGetter, Function<C, T> posGetter) {
      for (C connection : connections) {
         if (Objects.equals(dimensionGetter.apply(connection), dimension) && Objects.equals(posGetter.apply(connection), target)) {
            return connection;
         }
      }

      return null;
   }

   private static <T, C, D, F> boolean hasEndpoint(
      Iterable<C> connections, D dimension, T target, F face, Function<C, D> dimensionGetter, Function<C, T> posGetter, Function<C, F> faceGetter
   ) {
      for (C connection : connections) {
         if (Objects.equals(dimensionGetter.apply(connection), dimension)
            && Objects.equals(posGetter.apply(connection), target)
            && Objects.equals(faceGetter.apply(connection), face)) {
            return true;
         }
      }

      return false;
   }

   public static record Plan<T>(boolean deselecting, List<T> disconnect, List<T> update, List<T> connect) {
      public boolean hasChanges() {
         return !this.disconnect.isEmpty() || !this.update.isEmpty() || !this.connect.isEmpty();
      }
   }
}
