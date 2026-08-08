package com.moakiee.thunderbolt.ae2.timewheel;

import appeng.api.config.Actionable;
import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.hooks.ticking.TickHandler;
import appeng.me.service.CraftingService;
import com.moakiee.thunderbolt.ae2.batch.TickProviderDispatchSchedule;
import com.moakiee.thunderbolt.ae2.crafting.ExtendedCraftingCpuCluster;
import com.moakiee.thunderbolt.ae2.crafting.LoopCraftingPlan;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public final class TimeWheelCraftingCpuPool implements ExtendedCraftingCpuCluster {
   private static final int PRODUCTIVE_DISPATCH_QUANTUM = 32;
   private static final int DATA_VERSION = 1;
   private static final String TAG_VERSION = "version";
   private static final String TAG_CPUS = "cpus";
   private static final String TAG_ID = "id";
   private static final String TAG_RESERVED_BYTES = "reservedBytes";
   private static final String TAG_STATE = "state";
   private final TimeWheelCraftingCpuPoolHost host;
   private final Map<UUID, TimeWheelCraftingCpuPool.PoolEntry> activeCpus = new LinkedHashMap<>();
   private long totalStorage;
   private int sharedCoProcessors;
   private long maxCopiesPerTick;
   private boolean unboundedBatch;
   private boolean fastPlanningEnabled = true;
   private long remainingStorage;
   private boolean cpuListChanged;
   private final TickProviderDispatchSchedule dispatchSchedule = new TickProviderDispatchSchedule();

   public TimeWheelCraftingCpuPool(TimeWheelCraftingCpuPoolHost host, long totalStorage, int sharedCoProcessors, long maxCopiesPerTick, boolean unboundedBatch) {
      if (totalStorage < 0L) {
         throw new IllegalArgumentException("Total crafting storage must not be negative.");
      } else if (sharedCoProcessors < 0) {
         throw new IllegalArgumentException("Shared co-processors must not be negative.");
      } else {
         this.host = host;
         this.totalStorage = totalStorage;
         this.sharedCoProcessors = sharedCoProcessors;
         this.maxCopiesPerTick = Math.max(1L, maxCopiesPerTick);
         this.unboundedBatch = unboundedBatch;
         this.remainingStorage = totalStorage;
      }
   }

   public TimeWheelCraftingCpuPoolHost getHost() {
      return this.host;
   }

   @Override
   public boolean isFastPlanningEnabled() {
      return this.fastPlanningEnabled;
   }

   public void setFastPlanningEnabled(boolean enabled) {
      this.fastPlanningEnabled = enabled;
   }

   public void reconfigure(long totalStorage, int sharedCoProcessors, long maxCopiesPerTick, boolean unboundedBatch) {
      if (!this.activeCpus.isEmpty()) {
         throw new IllegalStateException("Cannot reconfigure a time-wheel CPU pool with retained state.");
      } else if (totalStorage < 0L) {
         throw new IllegalArgumentException("Total crafting storage must not be negative.");
      } else if (sharedCoProcessors < 0) {
         throw new IllegalArgumentException("Shared co-processors must not be negative.");
      } else {
         this.totalStorage = totalStorage;
         this.sharedCoProcessors = sharedCoProcessors;
         this.maxCopiesPerTick = Math.max(1L, maxCopiesPerTick);
         this.unboundedBatch = unboundedBatch;
         this.remainingStorage = totalStorage;
         this.cpuListChanged = true;
         this.host.markCpuDirty();
      }
   }

   public List<TimeWheelCraftingCPU> getActiveCpus() {
      ArrayList<TimeWheelCraftingCPU> result = new ArrayList<>(this.activeCpus.size());

      for (TimeWheelCraftingCpuPool.PoolEntry entry : this.activeCpus.values()) {
         result.add(entry.cpu());
      }

      return List.copyOf(result);
   }

   @Override
   public long tickCraftingLogic(IEnergyService energyService, CraftingService craftingService) {
      this.resolvePendingLoad();
      long[] latestChange = new long[]{Long.MIN_VALUE};
      int successfulDispatchBudget = this.sharedCoProcessors >= 2147483646 ? Integer.MAX_VALUE : this.sharedCoProcessors + 1;
      this.dispatchSchedule.beginTick(TickHandler.instance().getCurrentTick());
      ArrayList<TimeWheelCraftingCpuPool.ScheduledCpu> scheduledCpus = new ArrayList<>(this.activeCpus.size());

      for (TimeWheelCraftingCpuPool.PoolEntry entry : List.copyOf(this.activeCpus.values())) {
         scheduledCpus.add(new TimeWheelCraftingCpuPool.ScheduledCpu(entry, this.unboundedBatch ? Long.MAX_VALUE : this.maxCopiesPerTick));
      }

      try {
         ProductiveDispatchScheduler.run(successfulDispatchBudget, 32, scheduledCpus, (scheduledx, allowance) -> {
            if (scheduledx.remainingCopies <= 0L) {
               return 0;
            } else {
               Ae2LtTimeWheelCraftingCpuLogic.TickUsage usage = this.tickScheduledCpu(scheduledx, allowance, energyService, craftingService);
               latestChange[0] = Math.max(latestChange[0], scheduledx.entry.cpu().getCraftingLogic().getWaitingKeysModifiedOnTick());
               return usage.successfulDispatches();
            }
         });
      } finally {
         for (TimeWheelCraftingCpuPool.ScheduledCpu scheduled : scheduledCpus) {
            scheduled.entry.cpu().getCraftingLogic().finishPhysicalSchedulingTick();
         }
      }

      this.rotateSchedulingOrder();
      this.removeDrainedCpus();
      return latestChange[0];
   }

   private Ae2LtTimeWheelCraftingCpuLogic.TickUsage tickScheduledCpu(
      TimeWheelCraftingCpuPool.ScheduledCpu scheduled, int dispatchBudget, IEnergyService energyService, CraftingService craftingService
   ) {
      Ae2LtTimeWheelCraftingCpuLogic.TickUsage usage = scheduled.entry
         .cpu()
         .getCraftingLogic()
         .tickCraftingLogic(energyService, craftingService, dispatchBudget, scheduled.remainingCopies, this.dispatchSchedule);
      if (scheduled.remainingCopies != Long.MAX_VALUE) {
         scheduled.remainingCopies = Math.max(0L, scheduled.remainingCopies - usage.dispatchedCopies());
      }

      return usage;
   }

   private void rotateSchedulingOrder() {
      if (this.activeCpus.size() > 1) {
         Iterator<Entry<UUID, TimeWheelCraftingCpuPool.PoolEntry>> iterator = this.activeCpus.entrySet().iterator();
         if (iterator.hasNext()) {
            Entry<UUID, TimeWheelCraftingCpuPool.PoolEntry> first = iterator.next();
            UUID id = first.getKey();
            TimeWheelCraftingCpuPool.PoolEntry entry = first.getValue();
            iterator.remove();
            this.activeCpus.put(id, entry);
         }
      }
   }

   @Override
   public void addWaitingKeys(Set<AEKey> waitingKeys) {
      for (TimeWheelCraftingCpuPool.PoolEntry entry : this.activeCpus.values()) {
         entry.cpu().getCraftingLogic().getAllWaitingFor(waitingKeys);
      }
   }

   @Override
   public long insert(AEKey what, long amount, Actionable mode) {
      long inserted = 0L;

      for (TimeWheelCraftingCpuPool.PoolEntry entry : this.activeCpus.values()) {
         if (inserted >= amount) {
            break;
         }

         inserted += entry.cpu().getCraftingLogic().insert(what, amount - inserted, mode);
      }

      return inserted;
   }

   @Override
   public long getRequestedAmount(AEKey what) {
      long requested = 0L;

      for (TimeWheelCraftingCpuPool.PoolEntry entry : this.activeCpus.values()) {
         requested = saturatingAdd(requested, entry.cpu().getCraftingLogic().getWaitingFor(what));
      }

      return requested;
   }

   @Override
   public void restoreCraftingLinks(Consumer<CraftingLink> consumer) {
      for (TimeWheelCraftingCpuPool.PoolEntry entry : this.activeCpus.values()) {
         if (entry.cpu().getCraftingLogic().getLastLink() instanceof CraftingLink link) {
            consumer.accept(link);
         }
      }
   }

   @Override
   public boolean consumeCpuListChanged() {
      boolean changed = this.cpuListChanged;
      this.cpuListChanged = false;
      return changed;
   }

   @Override
   public ICraftingSubmitResult submitJob(IGrid grid, ICraftingPlan plan, IActionSource src, @Nullable ICraftingRequester requester) {
      if (this.isActive() && this.canAcceptPlan(plan)) {
         boolean infiniteStorage = this.hasInfiniteStorage();
         long reservedBytes = infiniteStorage ? 0L : Math.max(0L, plan.bytes());
         if (!infiniteStorage && reservedBytes > this.remainingStorage) {
            return CraftingSubmitResult.CPU_TOO_SMALL;
         } else {
            UUID id = UUID.randomUUID();
            long cpuStorage = infiniteStorage ? Long.MAX_VALUE : reservedBytes;
            TimeWheelCraftingCPU cpu = new TimeWheelCraftingCPU(this.host, cpuStorage, this.sharedCoProcessors, this.maxCopiesPerTick, this.unboundedBatch);
            TimeWheelCraftingCpuPool.PoolEntry entry = new TimeWheelCraftingCpuPool.PoolEntry(id, reservedBytes, cpu);
            this.activeCpus.put(id, entry);
            this.remainingStorage -= reservedBytes;
            ICraftingSubmitResult result = cpu.submitJob(grid, plan, src, requester);
            if (!result.successful()) {
               if (!cpu.hasPersistentState()) {
                  this.activeCpus.remove(id);
                  this.recalculateRemainingStorage();
               } else {
                  this.cpuListChanged = true;
                  this.host.markCpuDirty();
               }

               return result;
            } else {
               this.cpuListChanged = true;
               this.host.markCpuDirty();
               return result;
            }
         }
      } else {
         return CraftingSubmitResult.CPU_OFFLINE;
      }
   }

   @Override
   public boolean canAcceptPlan(ICraftingPlan plan) {
      return plan instanceof LoopCraftingPlan loopPlan ? loopPlan.canRunOn(this.host) : ExtendedCraftingCpuCluster.super.canAcceptPlan(plan);
   }

   public void cancelAll() {
      for (TimeWheelCraftingCpuPool.PoolEntry entry : List.copyOf(this.activeCpus.values())) {
         entry.cpu().cancelJob();
      }

      this.removeDrainedCpus();
   }

   public void tryReleaseContents() {
      for (TimeWheelCraftingCpuPool.PoolEntry entry : List.copyOf(this.activeCpus.values())) {
         entry.cpu().tryReleaseContents();
      }

      this.removeDrainedCpus();
   }

   public boolean hasPersistentState() {
      return !this.activeCpus.isEmpty();
   }

   public void writeToNBT(CompoundTag tag, Provider registries) {
      tag.putInt("version", 1);
      ListTag cpuList = new ListTag();

      for (TimeWheelCraftingCpuPool.PoolEntry entry : this.activeCpus.values()) {
         if (entry.cpu().hasPersistentState()) {
            CompoundTag state = new CompoundTag();
            entry.cpu().writeToNBT(state, registries);
            CompoundTag entryTag = new CompoundTag();
            entryTag.putUUID("id", entry.id());
            entryTag.putLong("reservedBytes", entry.reservedBytes());
            entryTag.put("state", state);
            cpuList.add(entryTag);
         }
      }

      if (!cpuList.isEmpty()) {
         tag.put("cpus", cpuList);
      }
   }

   public void readFromNBT(CompoundTag tag, Provider registries) {
      this.activeCpus.clear();
      this.remainingStorage = this.totalStorage;
      this.cpuListChanged = false;
      ListTag cpuList = tag.getList("cpus", 10);

      for (int i = 0; i < cpuList.size(); i++) {
         CompoundTag entryTag = cpuList.getCompound(i);
         if (entryTag.hasUUID("id") && entryTag.contains("reservedBytes", 4) && entryTag.contains("state", 10)) {
            UUID id = entryTag.getUUID("id");
            if (this.activeCpus.containsKey(id)) {
               id = UUID.randomUUID();
            }

            boolean infiniteStorage = this.hasInfiniteStorage();
            long reservedBytes = infiniteStorage ? 0L : Math.max(0L, entryTag.getLong("reservedBytes"));
            long cpuStorage = infiniteStorage ? Long.MAX_VALUE : reservedBytes;
            TimeWheelCraftingCPU cpu = new TimeWheelCraftingCPU(this.host, cpuStorage, this.sharedCoProcessors, this.maxCopiesPerTick, this.unboundedBatch);
            cpu.readFromNBT(entryTag.getCompound("state"), registries);
            this.activeCpus.put(id, new TimeWheelCraftingCpuPool.PoolEntry(id, reservedBytes, cpu));
         }
      }

      this.recalculateRemainingStorage();
      this.cpuListChanged = !this.activeCpus.isEmpty();
   }

   @Override
   public void prepareForCraftingService() {
      this.resolvePendingLoad();
   }

   public void resolvePendingLoad() {
      for (TimeWheelCraftingCpuPool.PoolEntry entry : this.activeCpus.values()) {
         entry.cpu().resolvePendingLoad();
      }

      this.removeDrainedCpus();
   }

   public void addRemovalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
      for (TimeWheelCraftingCpuPool.PoolEntry entry : this.activeCpus.values()) {
         entry.cpu().addRemovalDrops(level, pos, drops);
      }
   }

   public void clearRemovedContent() {
      for (TimeWheelCraftingCpuPool.PoolEntry entry : this.activeCpus.values()) {
         entry.cpu().clearRemovedContent();
      }

      this.activeCpus.clear();
      this.remainingStorage = this.totalStorage;
      this.cpuListChanged = true;
   }

   public void invalidate(Level level, BlockPos pos, List<ItemStack> drops) {
      this.addRemovalDrops(level, pos, drops);
      this.clearRemovedContent();
      this.host.markCpuDirty();
   }

   public boolean isBusy() {
      return false;
   }

   @Nullable
   public CraftingJobStatus getJobStatus() {
      return null;
   }

   public void cancelJob() {
   }

   public long getAvailableStorage() {
      return this.remainingStorage;
   }

   public long getTotalStorage() {
      return this.totalStorage;
   }

   public boolean hasInfiniteStorage() {
      return this.totalStorage == Long.MAX_VALUE;
   }

   public int getCoProcessors() {
      return this.sharedCoProcessors;
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

   @Override
   public boolean isActive() {
      return this.host.isCpuActive();
   }

   private void removeDrainedCpus() {
      boolean changed = false;
      Iterator<Entry<UUID, TimeWheelCraftingCpuPool.PoolEntry>> iterator = this.activeCpus.entrySet().iterator();

      while (iterator.hasNext()) {
         TimeWheelCraftingCpuPool.PoolEntry entry = iterator.next().getValue();
         if (!entry.cpu().hasPersistentState()) {
            iterator.remove();
            changed = true;
         }
      }

      if (changed) {
         this.recalculateRemainingStorage();
         this.cpuListChanged = true;
         this.host.markCpuDirty();
      }
   }

   private void recalculateRemainingStorage() {
      long remaining = this.totalStorage;

      for (TimeWheelCraftingCpuPool.PoolEntry entry : this.activeCpus.values()) {
         long reserved = entry.reservedBytes();
         remaining = reserved >= remaining ? 0L : remaining - reserved;
         if (remaining == 0L) {
            break;
         }
      }

      this.remainingStorage = remaining;
   }

   private static long saturatingAdd(long left, long right) {
      if (right <= 0L) {
         return left;
      } else {
         return left >= Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
      }
   }

   private static record PoolEntry(UUID id, long reservedBytes, TimeWheelCraftingCPU cpu) {
   }

   private static final class ScheduledCpu {
      private final TimeWheelCraftingCpuPool.PoolEntry entry;
      private long remainingCopies;

      private ScheduledCpu(TimeWheelCraftingCpuPool.PoolEntry entry, long remainingCopies) {
         this.entry = entry;
         this.remainingCopies = remainingCopies;
      }
   }
}
