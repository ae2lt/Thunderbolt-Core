package com.moakiee.thunderbolt.ae2.timewheel;

import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.GenericStack;
import appeng.crafting.execution.ElapsedTimeTracker;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public final class TimeWheelCraftingCPU implements ICraftingCPU {
   private static final long PROGRESS_SCALE = 2147483647L;
   private final TimeWheelCraftingCpuHost host;
   private final long storageBytes;
   private final int coProcessors;
   private final long maxCopiesPerTick;
   private final boolean unboundedBatch;
   private final Ae2LtTimeWheelCraftingCpuLogic craftingLogic = new Ae2LtTimeWheelCraftingCpuLogic(this);
   private GenericStack finalOutput;

   public TimeWheelCraftingCPU(TimeWheelCraftingCpuHost host, long storageBytes, int coProcessors, long maxCopiesPerTick, boolean unboundedBatch) {
      this.host = host;
      this.storageBytes = storageBytes;
      this.coProcessors = coProcessors;
      this.maxCopiesPerTick = Math.max(1L, maxCopiesPerTick);
      this.unboundedBatch = unboundedBatch;
   }

   public Ae2LtTimeWheelCraftingCpuLogic getCraftingLogic() {
      return this.craftingLogic;
   }

   public TimeWheelCraftingCpuHost getHost() {
      return this.host;
   }

   public boolean isBusy() {
      return this.craftingLogic.hasPersistentState();
   }

   @Nullable
   public CraftingJobStatus getJobStatus() {
      GenericStack output = this.craftingLogic.getFinalJobOutput();
      if (output == null) {
         return null;
      } else {
         ElapsedTimeTracker elapsedTimeTracker = this.craftingLogic.getElapsedTimeTracker();
         long remaining = remainingProgressUnits(elapsedTimeTracker.getProgress());
         long progress = Math.max(0L, 2147483647L - remaining);
         return new CraftingJobStatus(output, 2147483647L, progress, elapsedTimeTracker.getElapsedTime());
      }
   }

   private static long remainingProgressUnits(float progress) {
      return (long)(2.147483647E9 - (double)progress * 2.147483647E9);
   }

   public void cancelJob() {
      this.craftingLogic.cancel();
   }

   public long getAvailableStorage() {
      return this.storageBytes;
   }

   public int getCoProcessors() {
      return this.coProcessors;
   }

   public int getSuccessfulDispatchesPerTick() {
      return this.coProcessors >= 2147483646 ? Integer.MAX_VALUE : this.coProcessors + 1;
   }

   public long getMaxCopiesPerTick() {
      return this.maxCopiesPerTick;
   }

   public boolean hasUnboundedBatch() {
      return this.unboundedBatch;
   }

   @Nullable
   public Component getName() {
      return this.host.getDisplayName();
   }

   public CpuSelectionMode getSelectionMode() {
      return this.host.getSelectionMode();
   }

   public boolean isActive() {
      return this.host.isCpuActive();
   }

   @Nullable
   public IGrid getGrid() {
      return this.host.getGrid();
   }

   public IActionSource getSrc() {
      return this.host.getActionSource();
   }

   public Level getLevel() {
      return this.host.getLevel();
   }

   public boolean canBeAutoSelectedFor(IActionSource source) {
      return switch (this.getSelectionMode()) {
         case ANY -> true;
         case PLAYER_ONLY -> source.player().isPresent();
         case MACHINE_ONLY -> source.player().isEmpty();
         default -> throw new IllegalStateException("Unsupported CPU selection mode: " + this.getSelectionMode());
      };
   }

   public boolean isPreferredFor(IActionSource source) {
      return switch (this.getSelectionMode()) {
         case ANY -> false;
         case PLAYER_ONLY -> source.player().isPresent();
         case MACHINE_ONLY -> source.player().isEmpty();
         default -> throw new IllegalStateException("Unsupported CPU selection mode: " + this.getSelectionMode());
      };
   }

   public ICraftingSubmitResult submitJob(IGrid grid, ICraftingPlan plan, IActionSource src, @Nullable ICraftingRequester requester) {
      return this.craftingLogic.trySubmitJob(grid, plan, src, requester);
   }

   public void updateOutput(@Nullable GenericStack stack) {
      if (stack != null && stack.amount() <= 0L) {
         stack = null;
      }

      this.finalOutput = stack;
   }

   @Nullable
   public GenericStack getDisplayedOutput() {
      return this.finalOutput;
   }

   public void markDirty() {
      this.host.markCpuDirty();
   }

   public void writeToNBT(CompoundTag tag, Provider registries) {
      this.craftingLogic.writeToNBT(tag, registries);
   }

   public boolean hasPersistentState() {
      return this.craftingLogic.hasPersistentState();
   }

   public void readFromNBT(CompoundTag tag, Provider registries) {
      this.craftingLogic.readFromNBT(tag, registries);
   }

   public void resolvePendingLoad() {
      this.craftingLogic.resolvePendingLoad();
   }

   public void addRemovalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
      this.craftingLogic.addStoredDrops(level, pos, drops);
   }

   public void clearRemovedContent() {
      this.craftingLogic.clearRemovedContent();
   }

   void tryReleaseContents() {
      this.craftingLogic.tryReleaseContents();
   }
}
