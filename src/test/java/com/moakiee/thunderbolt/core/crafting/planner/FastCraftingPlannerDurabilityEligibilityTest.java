package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;

import org.junit.jupiter.api.Test;

class FastCraftingPlannerDurabilityEligibilityTest {
    private static final AEKey FULL_TOOL = new TestKey("tool", "full");
    private static final AEKey DAMAGED_TOOL = new TestKey("tool", "damaged");

    @Test
    void strictPatternDoesNotTreatRejectedDamagedToolAsReusable() {
        var input = new TestInput(false);

        assertFalse(FastCraftingPlanner.acceptsDurabilityRemainder(
                input, DAMAGED_TOOL, null));
    }

    @Test
    void substitutionPatternKeepsAcceptedDamagedToolInDurabilityChain() {
        var input = new TestInput(true);

        assertTrue(FastCraftingPlanner.acceptsDurabilityRemainder(
                input, DAMAGED_TOOL, null));
    }

    @Test
    void missingRemainderCannotContinueDurabilityChain() {
        assertFalse(FastCraftingPlanner.acceptsDurabilityRemainder(
                new TestInput(true), null, null));
    }

    private record TestInput(boolean allowVariants) implements IPatternDetails.IInput {
        @Override
        public GenericStack[] getPossibleInputs() {
            return new GenericStack[] { new GenericStack(FULL_TOOL, 1) };
        }

        @Override
        public long getMultiplier() {
            return 1;
        }

        @Override
        public boolean isValid(AEKey key, Level level) {
            return allowVariants
                    ? FULL_TOOL.dropSecondary().equals(key.dropSecondary())
                    : FULL_TOOL.equals(key);
        }

        @Override
        public AEKey getRemainingKey(AEKey key) {
            return FULL_TOOL.equals(key) ? DAMAGED_TOOL : null;
        }
    }

    private static final class TestKey extends AEKey {
        private static final TestKeyType TYPE = new TestKeyType();
        private final String id;
        private final String variant;

        private TestKey(String id, String variant) {
            this.id = id;
            this.variant = variant;
        }

        @Override
        public AEKeyType getType() {
            return TYPE;
        }

        @Override
        public AEKey dropSecondary() {
            return new TestKey(id, "");
        }

        @Override
        public CompoundTag toTag() {
            return new CompoundTag();
        }

        @Override
        public Object getPrimaryKey() {
            return id;
        }

        @Override
        public ResourceLocation getId() {
            return new ResourceLocation("thunderbolt_test", id);
        }

        @Override
        public void writeToPacket(FriendlyByteBuf data) {
        }

        @Override
        protected Component computeDisplayName() {
            return Component.literal(id + "#" + variant);
        }

        @Override
        public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof TestKey other
                    && id.equals(other.id)
                    && variant.equals(other.variant);
        }

        @Override
        public int hashCode() {
            return 31 * id.hashCode() + variant.hashCode();
        }
    }

    private static final class TestKeyType extends AEKeyType {
        private TestKeyType() {
            super(new ResourceLocation(
                            "thunderbolt_test", "durability_eligibility_key"),
                    TestKey.class, Component.literal("durability eligibility key"));
        }

        @Override
        public AEKey loadKeyFromTag(CompoundTag tag) {
            return null;
        }

        @Override
        public AEKey readFromPacket(FriendlyByteBuf input) {
            return null;
        }
    }
}
