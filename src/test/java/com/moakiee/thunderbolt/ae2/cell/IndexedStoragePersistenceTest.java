package com.moakiee.thunderbolt.core.storage.cell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class IndexedStoragePersistenceTest {
    private static final TestKey A = new TestKey("a");
    private static final TestKey B = new TestKey("b");

    @Test
    void loadingAgainClearsKeysAndAmountsFromThePreviousImage() {
        var storage = new IndexedStorage();
        storage.insert(A, 10L, Actionable.MODULATE);

        storage.load(encoded(List.of(B), new long[] {5L}, new long[] {0L}),
                IndexedStoragePersistenceTest::decode, null);

        assertFalse(storage.containsKey(A));
        assertEquals(0L, storage.getAmount(A));
        assertTrue(storage.containsKey(B));
        assertEquals(5L, storage.getAmount(B));
        assertEquals(1, storage.getTotalTypes());
        assertFalse(storage.needsPersist());
    }

    @Test
    void malformedAndDuplicateEntriesAreHealedByTheNextPersist() {
        var broken = new TestKey("broken");
        var storage = new IndexedStorage();
        storage.load(
                encoded(
                        List.of(A, A, broken, B),
                        new long[] {7L, 5L, 3L, -1L},
                        new long[] {0L, 0L, 0L, 0L}),
                IndexedStoragePersistenceTest::decode,
                null);

        assertEquals(12L, storage.getAmount(A));
        assertFalse(storage.containsKey(B));
        assertEquals(1, storage.getTotalTypes());
        assertTrue(storage.needsPersist());

        var healed = storage.persist(null, (key, ignored) -> key.toTag(), null);
        assertEquals(1, healed.getList("keys", CompoundTag.TAG_COMPOUND).size());
        assertEquals(12L, healed.getLongArray("lo")[0]);
        assertEquals(0L, healed.getLongArray("hi")[0]);
        assertFalse(storage.needsPersist());
    }

    @Test
    void duplicateAmountsSaturateAtTheMaximum126BitValue() {
        var storage = new IndexedStorage();
        storage.load(
                encoded(
                        List.of(A, A),
                        new long[] {Long.MAX_VALUE, 1L},
                        new long[] {Long.MAX_VALUE, 0L}),
                IndexedStoragePersistenceTest::decode,
                null);

        var healed = storage.persist(null, (key, ignored) -> key.toTag(), null);
        assertEquals(Long.MAX_VALUE, healed.getLongArray("lo")[0]);
        assertEquals(Long.MAX_VALUE, healed.getLongArray("hi")[0]);
        assertEquals(Long.MAX_VALUE, storage.getAmount(A));

        assertEquals(1L, storage.insert(A, 1L, Actionable.MODULATE));
        var saturated = storage.persist(healed, (key, ignored) -> key.toTag(), null);
        assertEquals(Long.MAX_VALUE, saturated.getLongArray("lo")[0]);
        assertEquals(Long.MAX_VALUE, saturated.getLongArray("hi")[0]);
    }

    @Test
    void truncatedAmountArraysUseZeroHighBitsAndAreRewrittenSafely() {
        var storage = new IndexedStorage();
        storage.load(
                encoded(List.of(A, B), new long[] {7L, 5L}, new long[] {0L}),
                IndexedStoragePersistenceTest::decode,
                null);

        assertEquals(7L, storage.getAmount(A));
        assertEquals(5L, storage.getAmount(B));
        assertTrue(storage.needsPersist());

        var healed = storage.persist(null, (key, ignored) -> key.toTag(), null);
        assertTrue(healed.getLongArray("hi").length >= 2);
        assertEquals(0L, healed.getLongArray("hi")[1]);
    }

    private static CompoundTag encoded(List<TestKey> keys, long[] lo, long[] hi) {
        var keyList = new ListTag();
        for (var key : keys) {
            var entry = new CompoundTag();
            entry.put("key", key.toTag());
            keyList.add(entry);
        }
        var root = new CompoundTag();
        root.put("keys", keyList);
        root.put("lo", new LongArrayTag(lo));
        root.put("hi", new LongArrayTag(hi));
        return root;
    }

    private static AEKey decode(CompoundTag tag, net.minecraft.core.HolderLookup.Provider ignored) {
        String name = tag.getString("name");
        if (name.equals("broken")) {
            throw new IllegalArgumentException("synthetic malformed key");
        }
        return new TestKey(name);
    }

    private static final class TestKeyType extends AEKeyType {
        private TestKeyType() {
            super(new ResourceLocation("thunderbolt_test", "indexed_storage"),
                    TestKey.class, Component.literal("indexed storage test"));
        }

        @Override
        public int getAmountPerByte() {
            return 1;
        }

        @Override
        public AEKey loadKeyFromTag(CompoundTag tag) {
            return decode(tag, null);
        }

        @Override
        public AEKey readFromPacket(FriendlyByteBuf input) {
            throw new UnsupportedOperationException("not used by persistence tests");
        }
    }

    private static final class TestKey extends AEKey {
        private static final TestKeyType TYPE = new TestKeyType();
        private final String name;

        private TestKey(String name) {
            this.name = name;
        }

        @Override
        public AEKeyType getType() {
            return TYPE;
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public CompoundTag toTag() {
            var tag = new CompoundTag();
            tag.putString("name", name);
            return tag;
        }

        @Override
        public Object getPrimaryKey() {
            return name;
        }

        @Override
        public ResourceLocation getId() {
            return new ResourceLocation("thunderbolt_test", name);
        }

        @Override
        public void writeToPacket(FriendlyByteBuf data) {
            throw new UnsupportedOperationException("not used by persistence tests");
        }

        @Override
        protected Component computeDisplayName() {
            return Component.literal(name);
        }

        @Override
        public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
            // No drops are needed for persistence tests.
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TestKey key && name.equals(key.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name);
        }
    }
}
