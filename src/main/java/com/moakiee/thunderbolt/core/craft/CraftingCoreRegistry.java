package com.moakiee.thunderbolt.core.craft;

import appeng.core.AELog;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Set;

public final class CraftingCoreRegistry {
   private final Set<Sweepable> active = Collections.newSetFromMap(new IdentityHashMap<>());

   public void markActive(Sweepable sweepable) {
      this.active.add(sweepable);
   }

   public void markInactive(Sweepable sweepable) {
      this.active.remove(sweepable);
   }

   public void tickAll() {
      if (!this.active.isEmpty()) {
         Iterator<Sweepable> it = this.active.iterator();

         while (it.hasNext()) {
            Sweepable sweepable = it.next();

            try {
               if (!sweepable.sweepTick()) {
                  it.remove();
               }
            } catch (Throwable var4) {
               AELog.warn("[ae2lt] crafting core sweep failed for %s; removing. %s", new Object[]{sweepable, var4});
               it.remove();
            }
         }
      }
   }

   public void clear() {
      this.active.clear();
   }
}
