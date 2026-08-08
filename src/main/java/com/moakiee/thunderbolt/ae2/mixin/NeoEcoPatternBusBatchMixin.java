package com.moakiee.thunderbolt.ae2.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;
import com.moakiee.thunderbolt.CoreConfig;
import com.moakiee.thunderbolt.ae2.api.crafting.BatchDispatchContext;
import com.moakiee.thunderbolt.ae2.api.crafting.IBatchCraftingProvider;
import com.moakiee.thunderbolt.ae2.batch.NeoEcoPatternBusBatchBridge;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

@Pseudo
@Mixin(
   targets = {"cn.dancingsnow.neoecoae.blocks.entity.crafting.ECOCraftingPatternBusBlockEntity"},
   remap = false
)
public abstract class NeoEcoPatternBusBatchMixin implements IBatchCraftingProvider {
   @Unique
   private long ae2lt$batchLimitRulesVersion = Long.MIN_VALUE;
   @Unique
   private long ae2lt$batchCopyLimit = Long.MAX_VALUE;

   @Override
   public long getBatchCapacity(IPatternDetails details) {
      long capacity = NeoEcoPatternBusBatchBridge.capacity(this, details);
      return Math.min(capacity, this.ae2lt$batchCopyLimit());
   }

   @Override
   public long pushBatch(IPatternDetails details, KeyCounter[] oneCopyTemplate, long maxCraft) {
      return maxCraft;
   }

   @Override
   public long pushBatch(BatchDispatchContext context) {
      long limited = Math.min(context.maxCraft(), this.ae2lt$batchCopyLimit());
      if (limited <= 0L) {
         return context.maxCraft();
      } else {
         long limitedLeftover = NeoEcoPatternBusBatchBridge.pushBatch(
            this, new BatchDispatchContext(context.details(), context.oneCopyTemplate(), limited, context.level(), context.craftingJobId())
         );
         long accepted = limited - Math.max(0L, Math.min(limitedLeftover, limited));
         return context.maxCraft() - accepted;
      }
   }

   @Unique
   private long ae2lt$batchCopyLimit() {
      CoreConfig.BatchCopyLimitRules rules = CoreConfig.batchCopyLimitRules();
      if (this.ae2lt$batchLimitRulesVersion != rules.version()) {
         BlockEntity self = (BlockEntity)(Object)this;
         ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(self.getBlockState().getBlock());
         this.ae2lt$batchCopyLimit = blockId != null ? rules.limit(blockId.toString()) : Long.MAX_VALUE;
         this.ae2lt$batchLimitRulesVersion = rules.version();
      }

      return this.ae2lt$batchCopyLimit;
   }
}
