package com.moakiee.thunderbolt.api.wireless;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

class WirelessConnectionListsTest {

    @Test
    void malformedEntryDoesNotDiscardValidNeighbors() {
        var root = rootWithEntries(10, -1, 20);
        var decoded = new ArrayList<TestConnection>();

        WirelessConnectionLists.readTagList(root, "Connections", decoded, 8, tag -> {
            int id = tag.getInt("Id");
            if (id < 0) throw new IllegalArgumentException("corrupt test entry");
            return connection(id, Direction.NORTH);
        });

        assertEquals(java.util.List.of(
                connection(10, Direction.NORTH),
                connection(20, Direction.NORTH)), decoded);
    }

    @Test
    void duplicateTargetsAreHealedAndLastValueWins() {
        var root = rootWithEntries(1, 2, 1);
        var decoded = new ArrayList<TestConnection>();

        WirelessConnectionLists.readTagList(root, "Connections", decoded, 8, tag -> {
            int id = tag.getInt("Id");
            return connection(id, id == 1 && tag.getBoolean("Last")
                    ? Direction.UP : Direction.DOWN);
        });

        assertEquals(2, decoded.size());
        assertEquals(connection(1, Direction.UP), decoded.get(0));
        assertEquals(connection(2, Direction.DOWN), decoded.get(1));
    }

    @Test
    void duplicatesDoNotConsumeTheUniqueConnectionLimit() {
        var root = rootWithEntries(1, 1, 2, 3);
        var decoded = new ArrayList<TestConnection>();

        WirelessConnectionLists.readTagList(root, "Connections", decoded, 2,
                tag -> connection(tag.getInt("Id"), Direction.DOWN));

        assertEquals(java.util.List.of(
                connection(1, Direction.DOWN),
                connection(2, Direction.DOWN)), decoded);
    }

    private static CompoundTag rootWithEntries(int... ids) {
        var root = new CompoundTag();
        var entries = new ListTag();
        for (int i = 0; i < ids.length; i++) {
            var entry = new CompoundTag();
            entry.putInt("Id", ids[i]);
            entry.putBoolean("Last", i == ids.length - 1);
            entries.add(entry);
        }
        root.put("Connections", entries);
        return root;
    }

    private static TestConnection connection(int id, Direction face) {
        return new TestConnection(new BlockPos(id, 0, 0), face);
    }

    private record TestConnection(BlockPos pos, Direction boundFace)
            implements WirelessConnectionRef {
        @Override
        public ResourceKey<Level> dimension() {
            return null;
        }

        @Override
        public CompoundTag toTag() {
            return new CompoundTag();
        }

        @Override
        public boolean sameTarget(ResourceKey<Level> ignoredDimension, BlockPos otherPos) {
            return pos.equals(otherPos);
        }
    }
}
