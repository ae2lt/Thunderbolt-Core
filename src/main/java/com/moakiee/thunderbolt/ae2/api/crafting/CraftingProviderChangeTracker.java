package com.moakiee.thunderbolt.ae2.api.crafting;

import appeng.api.networking.crafting.ICraftingService;
import appeng.hooks.ticking.TickHandler;
import appeng.me.service.CraftingService;
import com.moakiee.thunderbolt.ae2.mixin.CraftingServiceAccessor;

public final class CraftingProviderChangeTracker {
   private long lastCheckTick = Long.MIN_VALUE;

   public boolean shouldRecheck(ICraftingService service) {
      if (service instanceof CraftingService crafting) {
         long changedTick = ((CraftingServiceAccessor)crafting).thunderbolt$getCraftingProviders().getLastModifiedOnTick();
         return this.shouldRecheck(changedTick, TickHandler.instance().getCurrentTick());
      } else {
         return true;
      }
   }

   boolean shouldRecheck(long changedTick, long currentTick) {
      if (changedTick < this.lastCheckTick) {
         return false;
      } else {
         this.lastCheckTick = currentTick;
         return true;
      }
   }

   public void reset() {
      this.lastCheckTick = Long.MIN_VALUE;
   }
}
