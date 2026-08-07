package com.moakiee.thunderbolt.ae2.overload.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import com.moakiee.thunderbolt.ae2.overload.model.EncodedOverloadPattern;
import com.moakiee.thunderbolt.ae2.overload.model.MatchMode;

/**
 * Regression guard for the tolerant read path of {@link OverloadPatternPayloadTagCodec}:
 * a single damaged value inside one overload-pattern item must not fail the whole
 * payload read (item NBT can be corrupted, hand-edited or left over from old versions).
 */
class OverloadPatternPayloadTagCodecTest {

    @Test
    void emptyPayloadTagReadsAsInertDefaultInsteadOfThrowing() {
        var payload = OverloadPatternPayloadTagCodec.readPayload(new CompoundTag());

        assertNotNull(payload);
        assertEquals(PatternExecutionHostKind.OVERLOADED_PATTERN_PROVIDER, payload.requiredHostKind());
        // Missing source snapshot degrades to the inert air fallback.
        assertEquals(ResourceLocation.fromNamespaceAndPath("minecraft", "air"), payload.sourcePattern().itemId());
        assertTrue(payload.encodedPattern().inputSlots().isEmpty());
        assertTrue(payload.encodedPattern().outputSlots().isEmpty());
    }

    @Test
    void malformedSourcePatternIdFallsBackToInertSnapshot() {
        var tag = new CompoundTag();
        var brokenSource = new CompoundTag();
        brokenSource.putString("Item", "not a valid resource location");
        tag.put("SourcePattern", brokenSource);

        var payload = OverloadPatternPayloadTagCodec.readPayload(tag);

        assertEquals(ResourceLocation.fromNamespaceAndPath("minecraft", "air"), payload.sourcePattern().itemId());
    }

    @Test
    void duplicateSlotEntriesAreSkippedInsteadOfThrowing() {
        var rules = new CompoundTag();
        var inputs = new ListTag();
        inputs.add(slotEntry(3, "STRICT"));
        inputs.add(slotEntry(3, "ID_ONLY"));
        rules.put("Inputs", inputs);

        var decoded = OverloadPatternPayloadTagCodec.readEncodedPattern(rules);

        assertEquals(1, decoded.inputSlots().size());
        // Map.put replaces before putUnique throws, so the later duplicate wins;
        // the contract here is only that reading must not throw.
        assertEquals(MatchMode.ID_ONLY, decoded.inputSlot(3).orElseThrow().matchMode());
    }

    @Test
    void negativeSlotEntriesAreSkippedInsteadOfThrowing() {
        var rules = new CompoundTag();
        var outputs = new ListTag();
        outputs.add(slotEntry(-1, "STRICT"));
        outputs.add(slotEntry(2, "ID_ONLY"));
        rules.put("Outputs", outputs);

        var decoded = OverloadPatternPayloadTagCodec.readEncodedPattern(rules);

        assertEquals(1, decoded.outputSlots().size());
        assertEquals(MatchMode.ID_ONLY, decoded.outputSlot(2).orElseThrow().matchMode());
    }

    @Test
    void roundTripPreservesThePayload() {
        var snapshot = new SourcePatternSnapshot(ResourceLocation.fromNamespaceAndPath("minecraft", "stone"), null, null);
        var encoded = EncodedOverloadPattern.builder()
                .input(0, MatchMode.STRICT)
                .output(1, MatchMode.ID_ONLY)
                .build();
        var original = new OverloadPatternPayload(
                PatternExecutionHostKind.OVERLOADED_PATTERN_PROVIDER, snapshot, encoded);

        var decoded = OverloadPatternPayloadTagCodec.readPayload(
                OverloadPatternPayloadTagCodec.writePayload(original));

        assertEquals(original.requiredHostKind(), decoded.requiredHostKind());
        assertEquals(original.sourcePattern().itemId(), decoded.sourcePattern().itemId());
        assertEquals(MatchMode.STRICT, decoded.encodedPattern().inputSlot(0).orElseThrow().matchMode());
        assertEquals(MatchMode.ID_ONLY, decoded.encodedPattern().outputSlot(1).orElseThrow().matchMode());
    }

    private static CompoundTag slotEntry(int slot, String mode) {
        var entry = new CompoundTag();
        entry.putInt("Slot", slot);
        entry.putString("Mode", mode);
        return entry;
    }
}
