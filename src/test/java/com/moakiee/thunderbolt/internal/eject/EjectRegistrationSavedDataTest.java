package com.moakiee.thunderbolt.internal.eject;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

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

    @Test
    void duplicateRegistrationIsIdempotent() {
        var dimension = EjectRegistrationSavedData.dimension("minecraft:overworld");
        var registration = new EjectRegistrationSavedData.PersistentRegistration(
                dimension, new BlockPos(1, 64, 2), Direction.UP,
                dimension, new BlockPos(3, 65, 4));
        var data = new EjectRegistrationSavedData();

        data.add(registration);
        data.add(registration);

        assertEquals(java.util.List.of(registration), data.getAll());
    }

    @Test
    void malformedAndDuplicateSavedEntriesDoNotCreatePhantomEndpoints() {
        var dimension = EjectRegistrationSavedData.dimension("minecraft:overworld");
        var registration = new EjectRegistrationSavedData.PersistentRegistration(
                dimension, new BlockPos(12, 64, -9), Direction.WEST,
                dimension, new BlockPos(-42, 80, 7));
        var source = new EjectRegistrationSavedData();
        source.add(registration);
        var encoded = source.save(new CompoundTag());
        var entries = encoded.getList("Entries", Tag.TAG_COMPOUND);

        entries.add(entries.getCompound(0).copy());

        var missingPosition = entries.getCompound(0).copy();
        missingPosition.remove("IPos");
        entries.add(missingPosition);

        var invalidFace = entries.getCompound(0).copy();
        invalidFace.putInt("IFace", 99);
        entries.add(invalidFace);

        var invalidDimension = entries.getCompound(0).copy();
        invalidDimension.putString("IDim", "not a dimension id");
        entries.add(invalidDimension);

        assertEquals(java.util.List.of(registration),
                EjectRegistrationSavedData.load(encoded).getAll());
    }
}
