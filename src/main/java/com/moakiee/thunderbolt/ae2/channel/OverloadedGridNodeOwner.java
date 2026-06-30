package com.moakiee.thunderbolt.ae2.channel;

/**
 * Marker interface for grid participants that support elevated (128-channel) capacity.
 * Channel capacity is decided by the owner marker in the grid mixins; regular machine
 * block entities should keep their normal AE cable connection appearance.
 */
public interface OverloadedGridNodeOwner {
}
