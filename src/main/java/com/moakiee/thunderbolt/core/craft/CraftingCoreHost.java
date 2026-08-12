package com.moakiee.thunderbolt.core.craft;

import appeng.api.stacks.AEKey;

/**
 * World/grid access the {@link CraftingCore} needs to deliver assembled outputs. Capacity and
 * energy are deliberately absent: those are decided by the rate limiter above the core.
 */
public interface CraftingCoreHost {
    long getGameTime();

    /**
     * Whether the block entity hosting this core has been removed.
     *
     * <p>Named differently from {@code BlockEntity.isRemoved()} on purpose. Minecraft methods are
     * SRG-remapped in the host mod while this interface lives in a separate mod jar, so sharing the
     * vanilla name would make interface dispatch fail with {@link AbstractMethodError}.</p>
     */
    boolean isCraftingHostRemoved();

    boolean isConnected();

    long insertToNetwork(AEKey key, long amount);

    void spawnToWorld(AEKey key, long amount);
}
