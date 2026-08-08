package com.moakiee.thunderbolt.ae2.overload.cpu;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public final class OverloadCpuOwner {
   private final UUID craftingId;
   private final int logicIdentity;
   private final WeakReference<Object> logicRef;

   private OverloadCpuOwner(UUID craftingId, Object logic) {
      this.craftingId = Objects.requireNonNull(craftingId, "craftingId");
      this.logicIdentity = System.identityHashCode(logic);
      this.logicRef = new WeakReference<>(logic);
   }

   public static OverloadCpuOwner from(UUID craftingId, Object logic) {
      Objects.requireNonNull(craftingId, "craftingId");
      Objects.requireNonNull(logic, "logic");
      return new OverloadCpuOwner(craftingId, logic);
   }

   public UUID craftingId() {
      return this.craftingId;
   }

   public int logicIdentity() {
      return this.logicIdentity;
   }

   @Nullable
   public Object logic() {
      return this.logicRef.get();
   }

   @Override
   public String toString() {
      return "OverloadCpuOwner[craftingId=" + this.craftingId + ", logicIdentity=" + this.logicIdentity + "]";
   }
}
