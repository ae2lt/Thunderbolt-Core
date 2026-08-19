package com.moakiee.thunderbolt.compat.extendedaeplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import net.minecraft.world.item.Items;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;

import com.moakiee.thunderbolt.test.MinecraftTestBootstrap;

class ExtendedAePlusSuperMatrixBatchBridgeTest {
    static {
        MinecraftTestBootstrap.ensureInitialized();
    }

    @Test
    void scalesAnOwnedCopyWithoutMutatingTheBorrowedTemplate() {
        var key = AEItemKey.of(Items.IRON_INGOT);
        var source = new KeyCounter();
        source.add(key, 3L);

        var scaled = ExtendedAePlusSuperMatrixBatchBridge.scaleTemplate(
                new KeyCounter[] { source },
                4L);

        assertEquals(3L, source.get(key));
        assertEquals(12L, scaled[0].get(key));
    }

    @Test
    void rejectsScalingThatWouldOverflowAKeyCounter() {
        var key = AEItemKey.of(Items.IRON_INGOT);
        var source = new KeyCounter();
        source.add(key, Long.MAX_VALUE);

        assertNull(ExtendedAePlusSuperMatrixBatchBridge.scaleTemplate(
                new KeyCounter[] { source },
                2L));
        assertEquals(Long.MAX_VALUE, source.get(key));
    }
}
