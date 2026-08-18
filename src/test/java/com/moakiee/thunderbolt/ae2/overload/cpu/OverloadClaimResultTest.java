package com.moakiee.thunderbolt.ae2.overload.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;

import com.moakiee.thunderbolt.ae2.crafting.FinalOutputAccounting;

class OverloadClaimResultTest {
    private static final AEKey FINAL_KEY = new TestKey("final");
    private static final PendingOverloadOutputKey OUTPUT =
            new PendingOverloadOutputKey(
                    UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    "pattern",
                    0);

    @Test
    void standaloneCompletionDoesNotMakeTheCpuConsumeTheOutput() {
        var completed = requesterPreview().partitionRequester(1L, 1L);

        assertEquals(1L, completed.claimedForRequester());
        assertEquals(0L, completed.claimedForInventory());
        assertEquals(0L, FinalOutputAccounting.physicallyAcceptedAmount(
                completed.claimedForInventory(),
                completed.claimedForRequester(),
                0L));
    }

    @Test
    void deferredRequesterOutputBecomesCpuInventory() {
        var deferred = requesterPreview().partitionRequester(0L, 0L);

        assertEquals(0L, deferred.claimedForRequester());
        assertEquals(1L, deferred.claimedForInventory());
        assertEquals(1L, FinalOutputAccounting.physicallyAcceptedAmount(
                deferred.claimedForInventory(),
                deferred.claimedForRequester(),
                0L));
    }

    private static OverloadClaimResult requesterPreview() {
        return new OverloadClaimResult(1L, List.of(new PendingOverloadClaim(
                OUTPUT,
                1L,
                true,
                1L,
                FINAL_KEY,
                List.of(),
                false)));
    }

    private static final class TestKey extends AEKey {
        private final ResourceLocation id;

        private TestKey(String path) {
            id = new ResourceLocation(
                    "thunderbolt_test",
                    path);
        }

        @Override
        public AEKeyType getType() {
            throw new UnsupportedOperationException();
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public CompoundTag toTag() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object getPrimaryKey() {
            return id;
        }

        @Override
        public ResourceLocation getId() {
            return id;
        }

        @Override
        public void writeToPacket(FriendlyByteBuf buffer) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected Component computeDisplayName() {
            return Component.literal(id.toString());
        }

        @Override
        public void addDrops(
                long amount,
                List<ItemStack> drops,
                Level level,
                BlockPos pos) {
        }
    }
}
