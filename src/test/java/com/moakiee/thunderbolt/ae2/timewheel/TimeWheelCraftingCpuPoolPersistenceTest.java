package com.moakiee.thunderbolt.ae2.timewheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;

class TimeWheelCraftingCpuPoolPersistenceTest {

    @Test
    void duplicateCpuIdsAreSkippedInsteadOfCloningTheirState() {
        var host = new TestHost();
        var pool = new TimeWheelCraftingCpuPool(host, 100L, 0, 1L, false);
        var id = UUID.randomUUID();
        var tag = poolTag(1, cpuEntry(id, 10L), cpuEntry(id, 10L));

        pool.readFromNBT(tag, null);

        assertEquals(1, pool.getActiveCpus().size());
        assertEquals(90L, pool.getAvailableStorage());
        assertTrue(host.dirty);
    }

    @Test
    void emptyPoolRemovesAStaleCpuListFromReusedTag() {
        var pool = new TimeWheelCraftingCpuPool(new TestHost(), 100L, 0, 1L, false);
        var tag = poolTag(1, cpuEntry(UUID.randomUUID(), 10L));

        pool.writeToNBT(tag, null);

        assertFalse(tag.contains("cpus"));
        assertEquals(1, tag.getInt("version"));
    }

    @Test
    void unsupportedOlderVersionIsNotParsedAsTheCurrentSchema() {
        var host = new TestHost();
        var pool = new TimeWheelCraftingCpuPool(host, 100L, 0, 1L, false);

        pool.readFromNBT(poolTag(0, cpuEntry(UUID.randomUUID(), 10L)), null);

        assertTrue(pool.getActiveCpus().isEmpty());
        assertEquals(100L, pool.getAvailableStorage());
    }

    private static CompoundTag poolTag(int version, CompoundTag... cpuEntries) {
        var tag = new CompoundTag();
        tag.putInt("version", version);
        var cpus = new ListTag();
        for (var entry : cpuEntries) cpus.add(entry);
        tag.put("cpus", cpus);
        return tag;
    }

    private static CompoundTag cpuEntry(UUID id, long reservedBytes) {
        var entry = new CompoundTag();
        entry.putUUID("id", id);
        entry.putLong("reservedBytes", reservedBytes);
        var state = new CompoundTag();
        // A pending job tag keeps the virtual CPU persistent while its host level is unavailable.
        state.put("job", new CompoundTag());
        entry.put("state", state);
        return entry;
    }

    private static final class TestHost implements TimeWheelCraftingCpuPoolHost {
        private boolean dirty;

        @Override
        public TimeWheelCraftingCpuPool getTimeWheelCraftingCpuPool() {
            return null;
        }

        @Override
        public boolean isCpuActive() {
            return true;
        }

        @Nullable
        @Override
        public IGrid getGrid() {
            return null;
        }

        @Override
        public IActionSource getActionSource() {
            return null;
        }

        @Nullable
        @Override
        public Level getCpuLevel() {
            return null;
        }

        @Override
        public void markCpuDirty() {
            dirty = true;
        }

        @Override
        public Component getCpuDisplayName() {
            return Component.literal("test");
        }
    }
}
