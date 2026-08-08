package com.moakiee.thunderbolt.core.craft;

import appeng.api.stacks.AEKey;

public interface CraftingCoreHost {
   long getGameTime();

   boolean isRemoved();

   boolean isConnected();

   long insertToNetwork(AEKey var1, long var2);

   void spawnToWorld(AEKey var1, long var2);
}
