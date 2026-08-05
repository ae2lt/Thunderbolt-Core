package com.moakiee.thunderbolt.ae2.channel;

/**
 * Duck interface mixed into {@code GridNode} at runtime.
 * Marks whether this node belongs to an overloaded-controller subtree
 * in the BFS routing tree, and exposes a channel-count setter for
 * max-flow-based channel assignment.
 */
public interface OverloadedSubtreeNode {
    boolean ae2lt$isInOverloadedSubtree();

    void ae2lt$setUsedChannels(int channels);
}
