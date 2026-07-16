package com.moakiee.thunderbolt.core.craft;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import java.util.function.Supplier;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import com.moakiee.thunderbolt.ae2.batch.SharedBatchInputPattern;

public final class MolecularCopyAssembler implements CopyAssembler {
    private final Supplier<Level> levelSupplier;

    public MolecularCopyAssembler(Level level) {
        this(() -> level);
    }

    public MolecularCopyAssembler(Supplier<Level> levelSupplier) {
        this.levelSupplier = Objects.requireNonNull(levelSupplier);
    }

    @Override
    public AssembledCopy assembleOneCopy(IPatternDetails details, KeyCounter[] oneCopyInputs) {
        if (!(details instanceof IMolecularAssemblerSupportedPattern pattern)) {
            return null;
        }

        Level level = levelSupplier.get();
        if (level == null) {
            return null;
        }

        CraftingInput input = buildCraftingInput(pattern, oneCopyInputs);
        ItemStack output = pattern.assemble(input, level);
        if (output.isEmpty()) {
            return null;
        }

        var remainders = new ArrayList<Stack>();
        var sharedRemainders = new ArrayList<Stack>();
        var sharedRemainderKeys = new HashSet<appeng.api.stacks.AEKey>();
        if (details instanceof SharedBatchInputPattern shared) {
            var inputs = details.getInputs();
            for (int slot = 0; slot < inputs.length; slot++) {
                for (var possible : inputs[slot].getPossibleInputs()) {
                    if (!shared.isSharedBatchInput(slot, possible.what())) continue;
                    var remaining = inputs[slot].getRemainingKey(possible.what());
                    if (remaining != null) sharedRemainderKeys.add(remaining);
                }
            }
        }
        NonNullList<ItemStack> remainingItems = pattern.getRemainingItems(input);
        if (remainingItems != null) {
            for (var remainder : remainingItems) {
                if (!remainder.isEmpty()) {
                    var key = AEItemKey.of(remainder);
                    var stack = new Stack(key, remainder.getCount());
                    if (sharedRemainderKeys.contains(key)) sharedRemainders.add(stack);
                    else remainders.add(stack);
                }
            }
        }

        return new AssembledCopy(AEItemKey.of(output), output.getCount(),
                List.copyOf(remainders), List.copyOf(sharedRemainders));
    }

    private static CraftingInput buildCraftingInput(IMolecularAssemblerSupportedPattern pattern,
                                                    KeyCounter[] oneCopyInputs) {
        ItemStack[] grid = new ItemStack[9];
        for (int i = 0; i < grid.length; i++) {
            grid[i] = ItemStack.EMPTY;
        }

        pattern.fillCraftingGrid(oneCopyInputs, (slot, stack) -> {
            if (slot >= 0 && slot < grid.length && stack != null) {
                grid[slot] = stack;
            }
        });

        var items = NonNullList.withSize(9, ItemStack.EMPTY);
        for (int i = 0; i < grid.length; i++) {
            items.set(i, grid[i]);
        }
        return CraftingInput.of(3, 3, items);
    }
}
