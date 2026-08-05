package com.moakiee.thunderbolt.ae2.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;

import com.moakiee.thunderbolt.CoreConfig;
import com.moakiee.thunderbolt.ae2.api.crafting.BatchDispatchContext;
import com.moakiee.thunderbolt.ae2.api.crafting.IBatchCraftingProvider;
import com.moakiee.thunderbolt.ae2.batch.NeoEcoPatternBusBatchBridge;

/** Adds Thunderbolt's batch-provider contract to NeoECO's native verified batch bus. */
@Pseudo
@Mixin(
        targets = "cn.dancingsnow.neoecoae.blocks.entity.crafting.ECOCraftingPatternBusBlockEntity",
        remap = false)
public abstract class NeoEcoPatternBusBatchMixin implements IBatchCraftingProvider {
    @Unique
    private long ae2lt$batchLimitRulesVersion = Long.MIN_VALUE;
    @Unique
    private long ae2lt$batchCopyLimit = Long.MAX_VALUE;

    @Override
    public long getBatchCapacity(IPatternDetails details) {
        long capacity = NeoEcoPatternBusBatchBridge.capacity(this, details);
        return Math.min(capacity, ae2lt$batchCopyLimit());
    }

    @Override
    public long pushBatch(
            IPatternDetails details,
            KeyCounter[] oneCopyTemplate,
            long maxCraft) {
        return maxCraft;
    }

    @Override
    public long pushBatch(BatchDispatchContext context) {
        long limited = Math.min(context.maxCraft(), ae2lt$batchCopyLimit());
        if (limited <= 0L) {
            return context.maxCraft();
        }
        long limitedLeftover = NeoEcoPatternBusBatchBridge.pushBatch(
                this,
                new BatchDispatchContext(
                        context.details(),
                        context.oneCopyTemplate(),
                        limited,
                        context.level(),
                        context.craftingJobId()));
        // Java 17: Math.clamp is Java 21+.
        long accepted = limited - Math.min(Math.max(limitedLeftover, 0L), limited);
        return context.maxCraft() - accepted;
    }

    @Unique
    private long ae2lt$batchCopyLimit() {
        var rules = CoreConfig.batchCopyLimitRules();
        if (ae2lt$batchLimitRulesVersion != rules.version()) {
            var self = (BlockEntity) (Object) this;
            var blockId = BuiltInRegistries.BLOCK.getKey(self.getBlockState().getBlock());
            ae2lt$batchCopyLimit = blockId != null
                    ? rules.limit(blockId.toString())
                    : Long.MAX_VALUE;
            ae2lt$batchLimitRulesVersion = rules.version();
        }
        return ae2lt$batchCopyLimit;
    }
}
