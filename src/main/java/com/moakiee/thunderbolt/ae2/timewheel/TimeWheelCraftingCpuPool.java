package com.moakiee.thunderbolt.ae2.timewheel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.me.service.CraftingService;

/**
 * Shared-capacity time-wheel CPU that creates one virtual CPU per crafting job.
 */
public final class TimeWheelCraftingCpuPool implements ICraftingCPU, TimeWheelFastPlanningGate.CpuState {
    private static final int DATA_VERSION = 1;
    private static final String TAG_VERSION = "version";
    private static final String TAG_CPUS = "cpus";
    private static final String TAG_ID = "id";
    private static final String TAG_RESERVED_BYTES = "reservedBytes";
    private static final String TAG_STATE = "state";

    private final TimeWheelCraftingCpuPoolHost host;
    private final Map<UUID, PoolEntry> activeCpus = new LinkedHashMap<>();

    private long totalStorage;
    private int sharedCoProcessors;
    private long remainingStorage;
    private boolean cpuListChanged;

    public TimeWheelCraftingCpuPool(TimeWheelCraftingCpuPoolHost host) {
        this(host, 0L, 0);
    }

    public TimeWheelCraftingCpuPool(TimeWheelCraftingCpuPoolHost host,
                                    long totalStorage,
                                    int sharedCoProcessors) {
        if (totalStorage < 0L) {
            throw new IllegalArgumentException("Total crafting storage must not be negative.");
        }
        if (sharedCoProcessors < 0) {
            throw new IllegalArgumentException("Shared co-processors must not be negative.");
        }
        this.host = host;
        this.totalStorage = totalStorage;
        this.sharedCoProcessors = sharedCoProcessors;
        this.remainingStorage = totalStorage;
    }

    public void reconfigure(long totalStorage, int sharedCoProcessors) {
        if (!activeCpus.isEmpty()) {
            throw new IllegalStateException("Cannot reconfigure a time-wheel CPU pool with retained state.");
        }
        if (totalStorage < 0L) {
            throw new IllegalArgumentException("Total crafting storage must not be negative.");
        }
        if (sharedCoProcessors < 0) {
            throw new IllegalArgumentException("Shared co-processors must not be negative.");
        }
        this.totalStorage = totalStorage;
        this.sharedCoProcessors = sharedCoProcessors;
        this.remainingStorage = totalStorage;
        this.cpuListChanged = true;
        host.markCpuDirty();
    }

    public List<TimeWheelCraftingCPU> getActiveCpus() {
        var result = new ArrayList<TimeWheelCraftingCPU>(activeCpus.size());
        for (var entry : activeCpus.values()) {
            result.add(entry.cpu());
        }
        return List.copyOf(result);
    }

    public boolean containsCpu(ICraftingCPU cpu) {
        if (cpu == this) {
            return true;
        }
        for (var entry : activeCpus.values()) {
            if (entry.cpu() == cpu) {
                return true;
            }
        }
        return false;
    }

    public long tickCraftingLogic(IEnergyService energyService, CraftingService craftingService) {
        resolvePendingLoad();
        long latestChange = Long.MIN_VALUE;
        for (var entry : List.copyOf(activeCpus.values())) {
            var logic = entry.cpu().getCraftingLogic();
            logic.tickCraftingLogic(energyService, craftingService);
            latestChange = Math.max(latestChange, logic.getWaitingKeysModifiedOnTick());
        }
        removeDrainedCpus();
        return latestChange;
    }

    public void addWaitingKeys(Set<AEKey> waitingKeys) {
        for (var entry : activeCpus.values()) {
            entry.cpu().getCraftingLogic().getAllWaitingFor(waitingKeys);
        }
    }

    public long insert(AEKey what, long amount, Actionable mode) {
        long inserted = 0L;
        for (var entry : activeCpus.values()) {
            if (inserted >= amount) {
                break;
            }
            inserted += entry.cpu().getCraftingLogic().insert(what, amount - inserted, mode);
        }
        return inserted;
    }

    public long getRequestedAmount(AEKey what) {
        long requested = 0L;
        for (var entry : activeCpus.values()) {
            requested = saturatingAdd(requested, entry.cpu().getCraftingLogic().getWaitingFor(what));
        }
        return requested;
    }

    public void restoreCraftingLinks(Consumer<CraftingLink> consumer) {
        for (var entry : activeCpus.values()) {
            var maybeLink = entry.cpu().getCraftingLogic().getLastLink();
            if (maybeLink instanceof CraftingLink link) {
                consumer.accept(link);
            }
        }
    }

    public boolean consumeCpuListChanged() {
        boolean changed = cpuListChanged;
        cpuListChanged = false;
        return changed;
    }

    public ICraftingSubmitResult submitJob(IGrid grid,
                                            ICraftingPlan plan,
                                            IActionSource src,
                                            @Nullable ICraftingRequester requester) {
        if (!isActive()) {
            return CraftingSubmitResult.CPU_OFFLINE;
        }

        boolean infiniteStorage = hasInfiniteStorage();
        long reservedBytes = infiniteStorage ? 0L : Math.max(0L, plan.bytes());
        if (!infiniteStorage && reservedBytes > remainingStorage) {
            return CraftingSubmitResult.CPU_TOO_SMALL;
        }

        var id = UUID.randomUUID();
        long cpuStorage = infiniteStorage ? Long.MAX_VALUE : reservedBytes;
        var cpu = new TimeWheelCraftingCPU(host, cpuStorage, sharedCoProcessors);
        var entry = new PoolEntry(id, reservedBytes, cpu);
        activeCpus.put(id, entry);
        remainingStorage -= reservedBytes;

        var result = cpu.submitJob(grid, plan, src, requester);
        if (!result.successful()) {
            if (!cpu.hasPersistentState()) {
                activeCpus.remove(id);
                recalculateRemainingStorage();
            } else {
                cpuListChanged = true;
                host.markCpuDirty();
            }
            return result;
        }

        cpuListChanged = true;
        host.markCpuDirty();
        return result;
    }

    public void cancelAll() {
        for (var entry : List.copyOf(activeCpus.values())) {
            entry.cpu().cancelJob();
        }
        removeDrainedCpus();
    }

    public boolean hasPersistentState() {
        return !activeCpus.isEmpty();
    }

    public void writeToNBT(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt(TAG_VERSION, DATA_VERSION);
        var cpuList = new ListTag();
        for (var entry : activeCpus.values()) {
            if (!entry.cpu().hasPersistentState()) {
                continue;
            }
            var state = new CompoundTag();
            entry.cpu().writeToNBT(state, registries);

            var entryTag = new CompoundTag();
            entryTag.putUUID(TAG_ID, entry.id());
            entryTag.putLong(TAG_RESERVED_BYTES, entry.reservedBytes());
            entryTag.put(TAG_STATE, state);
            cpuList.add(entryTag);
        }
        if (!cpuList.isEmpty()) {
            tag.put(TAG_CPUS, cpuList);
        }
    }

    public void readFromNBT(CompoundTag tag, HolderLookup.Provider registries) {
        activeCpus.clear();
        remainingStorage = totalStorage;
        cpuListChanged = false;

        var cpuList = tag.getList(TAG_CPUS, Tag.TAG_COMPOUND);
        for (int i = 0; i < cpuList.size(); i++) {
            var entryTag = cpuList.getCompound(i);
            if (!entryTag.hasUUID(TAG_ID)
                    || !entryTag.contains(TAG_RESERVED_BYTES, Tag.TAG_LONG)
                    || !entryTag.contains(TAG_STATE, Tag.TAG_COMPOUND)) {
                continue;
            }

            var id = entryTag.getUUID(TAG_ID);
            if (activeCpus.containsKey(id)) {
                id = UUID.randomUUID();
            }
            boolean infiniteStorage = hasInfiniteStorage();
            long reservedBytes = infiniteStorage ? 0L : Math.max(0L, entryTag.getLong(TAG_RESERVED_BYTES));
            long cpuStorage = infiniteStorage ? Long.MAX_VALUE : reservedBytes;
            var cpu = new TimeWheelCraftingCPU(host, cpuStorage, sharedCoProcessors);
            cpu.readFromNBT(entryTag.getCompound(TAG_STATE), registries);
            activeCpus.put(id, new PoolEntry(id, reservedBytes, cpu));
        }
        recalculateRemainingStorage();
        cpuListChanged = !activeCpus.isEmpty();
    }

    public void resolvePendingLoad() {
        for (var entry : activeCpus.values()) {
            entry.cpu().resolvePendingLoad();
        }
        removeDrainedCpus();
    }

    public void addRemovalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        for (var entry : activeCpus.values()) {
            entry.cpu().addRemovalDrops(level, pos, drops);
        }
    }

    public void clearRemovedContent() {
        for (var entry : activeCpus.values()) {
            entry.cpu().clearRemovedContent();
        }
        activeCpus.clear();
        remainingStorage = totalStorage;
        cpuListChanged = true;
    }

    public void invalidate(Level level, BlockPos pos, List<ItemStack> drops) {
        addRemovalDrops(level, pos, drops);
        clearRemovedContent();
        host.markCpuDirty();
    }

    @Override
    public boolean isBusy() {
        return false;
    }

    @Nullable
    @Override
    public CraftingJobStatus getJobStatus() {
        return null;
    }

    @Override
    public void cancelJob() {
    }

    @Override
    public long getAvailableStorage() {
        return remainingStorage;
    }

    public long getTotalStorage() {
        return totalStorage;
    }

    public boolean hasInfiniteStorage() {
        return totalStorage == Long.MAX_VALUE;
    }

    @Override
    public int getCoProcessors() {
        return sharedCoProcessors;
    }

    @Nullable
    @Override
    public Component getName() {
        return host.getDisplayName();
    }

    @Override
    public CpuSelectionMode getSelectionMode() {
        return host.getSelectionMode();
    }

    @Override
    public boolean isActive() {
        return host.isCpuActive();
    }

    public boolean canBeAutoSelectedFor(IActionSource source) {
        return switch (getSelectionMode()) {
            case ANY -> true;
            case PLAYER_ONLY -> source.player().isPresent();
            case MACHINE_ONLY -> source.player().isEmpty();
        };
    }

    public boolean isPreferredFor(IActionSource source) {
        return switch (getSelectionMode()) {
            case ANY -> false;
            case PLAYER_ONLY -> source.player().isPresent();
            case MACHINE_ONLY -> source.player().isEmpty();
        };
    }

    private void removeDrainedCpus() {
        boolean changed = false;
        var iterator = activeCpus.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next().getValue();
            if (entry.cpu().hasPersistentState()) {
                continue;
            }
            iterator.remove();
            changed = true;
        }
        if (changed) {
            recalculateRemainingStorage();
            cpuListChanged = true;
            host.markCpuDirty();
        }
    }

    private void recalculateRemainingStorage() {
        long remaining = totalStorage;
        for (var entry : activeCpus.values()) {
            long reserved = entry.reservedBytes();
            remaining = reserved >= remaining ? 0L : remaining - reserved;
            if (remaining == 0L) {
                break;
            }
        }
        remainingStorage = remaining;
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0L) {
            return left;
        }
        return left >= Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private record PoolEntry(UUID id, long reservedBytes, TimeWheelCraftingCPU cpu) {
    }
}
