package com.moakiee.thunderbolt.internal.eject;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;

class EjectRegistrationSavedDataTest {
    @Test
    void roundTripsTheLegacyAe2ltSchema() {
        // 1.20.1: Registries.DIMENSION / ResourceKey.createRegistryKey would trigger
        // BuiltInRegistries bootstrap, which is unavailable in plain JUnit. The SavedData's own
        // dimension(String) factory builds keys without touching the registries.
        var overworld = EjectRegistrationSavedData.dimension("minecraft:overworld");
        var nether = EjectRegistrationSavedData.dimension("minecraft:the_nether");
        var expected = new EjectRegistrationSavedData.PersistentRegistration(
                overworld, new BlockPos(12, 64, -9), Direction.WEST,
                nether, new BlockPos(-42, 80, 7));

        var legacy = new EjectRegistrationSavedData();
        legacy.add(expected);
        CompoundTag encodedLegacyFile = legacy.save(new CompoundTag());

        var decodedByThunderbolt = EjectRegistrationSavedData.load(encodedLegacyFile);
        assertEquals(java.util.List.of(expected), decodedByThunderbolt.getAll());

        CompoundTag encodedNewFile = decodedByThunderbolt.save(new CompoundTag());
        assertEquals(java.util.List.of(expected),
                EjectRegistrationSavedData.load(encodedNewFile).getAll());
    }
}
