package com.moakiee.thunderbolt.mixin.compat.neoeco;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;

import com.moakiee.thunderbolt.CoreConfig;
import com.moakiee.thunderbolt.api.crafting.batch.BatchDispatchContext;
import com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider;
import com.moakiee.thunderbolt.core.crafting.batch.NeoEcoPatternBusBatchBridge;

/** Adds Thunderbolt's batch-provider contract to NeoECO's native verified batch bus. */
@Pseudo
@Mixin(
        targets = "cn.dancingsnow.neoecoae.blocks.entity.crafting.ECOCraftingPatternBusBlockEntity",
        remap = false)
public abstract class NeoEcoPatternBusBatchMixin implements IBatchCraftingProvider {
    @Unique
    private long thunderbolt$batchLimitRulesVersion = Long.MIN_VALUE;
    @Unique
    private long thunderbolt$batchCopyLimit = Long.MAX_VALUE;

    @Override
    public long getBatchCapacity(IPatternDetails details) {
        long capacity = NeoEcoPatternBusBatchBridge.capacity(this, details);
        return Math.min(capacity, thunderbolt$batchCopyLimit());
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
        long limited = Math.min(context.maxCraft(), thunderbolt$batchCopyLimit());
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
        long accepted = limited - Math.clamp(limitedLeftover, 0L, limited);
        return context.maxCraft() - accepted;
    }

    @Unique
    private long thunderbolt$batchCopyLimit() {
        var rules = CoreConfig.batchCopyLimitRules();
        if (thunderbolt$batchLimitRulesVersion != rules.version()) {
            var self = (BlockEntity) (Object) this;
            var blockId = BuiltInRegistries.BLOCK.getKey(self.getBlockState().getBlock());
            thunderbolt$batchCopyLimit = blockId != null
                    ? rules.limit(blockId.toString())
                    : Long.MAX_VALUE;
            thunderbolt$batchLimitRulesVersion = rules.version();
        }
        return thunderbolt$batchCopyLimit;
    }
}
