package com.moakiee.thunderbolt.ae2.overload.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import com.moakiee.thunderbolt.ae2.overload.pattern.SourcePatternSnapshot;

class OverloadCpuStatePersistenceTest {
    private static final ResourceLocation STONE_ID =
            new ResourceLocation("minecraft", "stone");
    private static final TestKey EXACT_KEY = new TestKey();

    @Test
    void malformedPendingEntriesAreSkippedWithoutDiscardingValidNeighbors() {
        var owner = owner();
        var exactKey = EXACT_KEY;
        var encoded = stateWithOnePending(owner, exactKey).toTag();
        var pending = encoded.getList("Pending", CompoundTag.TAG_COMPOUND);
        var valid = pending.getCompound(0);

        var duplicate = valid.copy();
        pending.add(duplicate);
        var badItemId = valid.copy();
        badItemId.putString("ItemId", "not a valid resource location");
        pending.add(badItemId);
        var missingExactKey = valid.copy();
        missingExactKey.remove("ExactTemplate");
        pending.add(missingExactKey);
        var emptyAmount = valid.copy();
        emptyAmount.putLong("RemainingAmount", 0L);
        pending.add(emptyAmount);
        var mismatchedItem = valid.copy();
        mismatchedItem.putString("ItemId", "minecraft:dirt");
        pending.add(mismatchedItem);
        var blankPattern = valid.copy();
        blankPattern.putString("PatternIdentity", " ");
        pending.add(blankPattern);
        var invalidSource = valid.copy();
        invalidSource.getCompound("SourcePattern").putString("Item", "invalid source id");
        pending.add(invalidSource);

        var decoded = OverloadCpuState.fromTag(owner, encoded, ignored -> exactKey);

        assertEquals(1, decoded.allPending().size());
        assertEquals(7L, decoded.allPending().iterator().next().remainingAmount());
    }

    @Test
    void missingLegacyOrderGetsAStableFallbackAndSequenceNeverWrapsNegative() {
        var owner = owner();
        var exactKey = EXACT_KEY;
        var encoded = stateWithOnePending(owner, exactKey).toTag();
        encoded.putLong("NextSequence", Long.MAX_VALUE);
        encoded.getList("Pending", CompoundTag.TAG_COMPOUND)
                .getCompound(0)
                .remove("RegisteredOrder");

        var decoded = OverloadCpuState.fromTag(owner, encoded, ignored -> exactKey);
        decoded.registerExpectedOutput(
                reference("second"), 1, STONE_ID, exactKey, 1L, false, null);
        decoded.registerExpectedOutput(
                reference("third"), 2, STONE_ID, exactKey, 1L, false, null);

        assertEquals(1L, decoded.allPending().iterator().next().registeredOrder());
        assertTrue(decoded.allPending().stream().allMatch(entry -> entry.registeredOrder() > 0L));
        assertEquals(2L, decoded.allPending().stream()
                .filter(entry -> entry.registeredOrder() == Long.MAX_VALUE)
                .count());
    }

    private static OverloadCpuState stateWithOnePending(
            OverloadCpuOwner owner, AEKey exactKey) {
        var state = new OverloadCpuState(owner);
        state.registerExpectedOutput(
                reference("first"), 0, STONE_ID, exactKey, 7L, false, null);
        return state;
    }

    private static OverloadPatternReference reference(String identity) {
        return new OverloadPatternReference(
                identity,
                new SourcePatternSnapshot(STONE_ID, null, null));
    }

    private static OverloadCpuOwner owner() {
        return OverloadCpuOwner.from(UUID.randomUUID(), new Object());
    }

    private static final class TestKeyType extends AEKeyType {
        private TestKeyType() {
            super(new ResourceLocation("thunderbolt_test", "item"),
                    TestKey.class, Component.literal("test item"));
        }

        @Override
        public int getAmountPerByte() {
            return 1;
        }

        @Override
        public AEKey loadKeyFromTag(CompoundTag tag) {
            return EXACT_KEY;
        }

        @Override
        public AEKey readFromPacket(FriendlyByteBuf input) {
            return EXACT_KEY;
        }
    }

    private static final class TestKey extends AEKey {
        private static final TestKeyType TYPE = new TestKeyType();

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
            tag.putString("id", STONE_ID.toString());
            return tag;
        }

        @Override
        public Object getPrimaryKey() {
            return STONE_ID;
        }

        @Override
        public ResourceLocation getId() {
            return STONE_ID;
        }

        @Override
        public void writeToPacket(FriendlyByteBuf data) {
            throw new UnsupportedOperationException("not used by persistence tests");
        }

        @Override
        protected Component computeDisplayName() {
            return Component.literal("test stone");
        }

        @Override
        public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
            // No drops are needed for persistence tests.
        }
    }
}
