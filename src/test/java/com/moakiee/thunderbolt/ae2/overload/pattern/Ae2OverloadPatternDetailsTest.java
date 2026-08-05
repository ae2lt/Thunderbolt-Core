package com.moakiee.thunderbolt.ae2.overload.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;

import com.moakiee.thunderbolt.ae2.overload.model.MatchMode;

import org.junit.jupiter.api.Test;

class Ae2OverloadPatternDetailsTest {
    @Test
    void idOnlyTemplateDropsSecondaryComponentsWhileStrictTemplateRemainsExact() {
        var componentKey = new TestKey("paper", "custom-name");
        var template = new GenericStack(componentKey, 7);

        var idOnly = Ae2OverloadPatternDetails.normalizeTemplate(template, MatchMode.ID_ONLY);
        assertEquals(componentKey.dropSecondary(), idOnly.what());
        assertEquals(7L, idOnly.amount());

        assertSame(template,
                Ae2OverloadPatternDetails.normalizeTemplate(template, MatchMode.STRICT));
    }

    private static final class TestKey extends AEKey {
        private static final TestKeyType TYPE = new TestKeyType();
        private final String id;
        private final String variant;

        private TestKey(String id, String variant) {
            this.id = id;
            this.variant = variant;
        }

        @Override public AEKeyType getType() { return TYPE; }
        @Override public AEKey dropSecondary() { return new TestKey(id, ""); }
        @Override public CompoundTag toTag(net.minecraft.core.HolderLookup.Provider registries) {
            return new CompoundTag();
        }
        @Override public Object getPrimaryKey() { return id; }
        @Override public ResourceLocation getId() {
            return ResourceLocation.fromNamespaceAndPath("thunderbolt_test", id);
        }
        @Override public void writeToPacket(RegistryFriendlyByteBuf data) { }
        @Override protected Component computeDisplayName() { return Component.literal(id + variant); }
        @Override public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) { }
        @Override public boolean hasComponents() { return !variant.isEmpty(); }
        @Override public boolean equals(Object obj) {
            return obj instanceof TestKey other && id.equals(other.id) && variant.equals(other.variant);
        }
        @Override public int hashCode() { return 31 * id.hashCode() + variant.hashCode(); }
    }

    private static final class TestKeyType extends AEKeyType {
        private TestKeyType() {
            super(ResourceLocation.fromNamespaceAndPath("thunderbolt_test", "normalized_overload_key"),
                    TestKey.class, Component.literal("normalized overload key"));
        }
        @Override public MapCodec<? extends AEKey> codec() { return null; }
        @Override public AEKey readFromPacket(RegistryFriendlyByteBuf input) { return null; }
    }
}
