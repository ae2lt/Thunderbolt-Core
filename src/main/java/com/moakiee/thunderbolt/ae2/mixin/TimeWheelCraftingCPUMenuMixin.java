package com.moakiee.thunderbolt.ae2.mixin;

import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.core.sync.packets.CraftingStatusPacket;
import appeng.crafting.execution.ElapsedTimeTracker;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.menu.AEBaseMenu;
import appeng.menu.me.common.IncrementalUpdateHelper;
import appeng.menu.me.crafting.CraftingCPUMenu;
import appeng.menu.me.crafting.CraftingStatus;
import appeng.menu.me.crafting.CraftingStatusEntry;
import com.moakiee.thunderbolt.ae2.timewheel.Ae2LtTimeWheelCraftingCpuLogic;
import com.moakiee.thunderbolt.ae2.timewheel.TimeWheelCraftingCPU;
import it.unimi.dsi.fastutil.objects.Object2LongMap.Entry;
import java.util.ArrayList;
import java.util.function.Consumer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(
   value = {CraftingCPUMenu.class},
   remap = false
)
public abstract class TimeWheelCraftingCPUMenuMixin extends AEBaseMenu {
   @Unique
   private static final long thunderbolt$PROGRESS_SCALE = 2147483647L;
   @Final
   @Shadow
   private IncrementalUpdateHelper incrementalUpdateHelper;
   @Final
   @Shadow
   private Consumer<AEKey> cpuChangeListener;
   @Unique
   private boolean thunderbolt$cachedSuspend;
   @Shadow
   private CraftingCPUCluster cpu;
   @Shadow
   public CpuSelectionMode schedulingMode;
   @Shadow
   public boolean cantStoreItems;
   @Unique
   private TimeWheelCraftingCPU thunderbolt$timeWheelCpu;

   protected TimeWheelCraftingCPUMenuMixin(MenuType<?> menuType, int id, Inventory playerInventory, Object host) {
      super(menuType, id, playerInventory, host);
   }

   @Inject(
      method = {"setCPU(Lappeng/api/networking/crafting/ICraftingCPU;)V"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void thunderbolt$setTimeWheelCpu(ICraftingCPU selected, CallbackInfo ci) {
      if (this.thunderbolt$timeWheelCpu != null) {
         this.thunderbolt$timeWheelCpu.getCraftingLogic().removeListener(this.cpuChangeListener);
         this.thunderbolt$timeWheelCpu = null;
      }

      if (selected instanceof TimeWheelCraftingCPU timeWheelCpu) {
         if (this.cpu != null) {
            this.cpu.craftingLogic.removeListener(this.cpuChangeListener);
            this.cpu = null;
         }

         this.incrementalUpdateHelper.reset();
         this.thunderbolt$cachedSuspend = false;
         this.thunderbolt$timeWheelCpu = timeWheelCpu;
         KeyCounter allItems = new KeyCounter();
         timeWheelCpu.getCraftingLogic().getAllItems(allItems);

         for (Entry<AEKey> entry : allItems) {
            this.incrementalUpdateHelper.addChange((AEKey)entry.getKey());
         }

         timeWheelCpu.getCraftingLogic().addListener(this.cpuChangeListener);
         ci.cancel();
      }
   }

   @Inject(
      method = {"cancelCrafting"},
      at = {@At("TAIL")}
   )
   private void thunderbolt$cancelTimeWheelCrafting(CallbackInfo ci) {
      if (!this.isClientSide() && this.thunderbolt$timeWheelCpu != null) {
         this.thunderbolt$timeWheelCpu.cancelJob();
      }
   }

   @Inject(
      method = {"removed"},
      at = {@At("TAIL")}
   )
   private void thunderbolt$removed(Player player, CallbackInfo ci) {
      if (this.thunderbolt$timeWheelCpu != null) {
         this.thunderbolt$timeWheelCpu.getCraftingLogic().removeListener(this.cpuChangeListener);
      }
   }

   @Inject(
      method = {"broadcastChanges"},
      at = {@At("HEAD")}
   )
   private void thunderbolt$broadcastTimeWheelStatus(CallbackInfo ci) {
      if (this.isServerSide() && this.thunderbolt$timeWheelCpu != null) {
         Ae2LtTimeWheelCraftingCpuLogic logic = this.thunderbolt$timeWheelCpu.getCraftingLogic();
         this.schedulingMode = this.thunderbolt$timeWheelCpu.getSelectionMode();
         this.cantStoreItems = logic.isCantStoreItems();
         if (this.incrementalUpdateHelper.hasChanges() || this.thunderbolt$cachedSuspend != logic.isJobSuspended()) {
            CraftingStatus status = thunderbolt$createStatus(this.incrementalUpdateHelper, logic);
            this.incrementalUpdateHelper.commitChanges();
            // 1.20.1 CraftingStatus has no suspended flag; keep the local state from the CPU logic.
            this.thunderbolt$cachedSuspend = logic.isJobSuspended();
            this.sendPacketToClient(new CraftingStatusPacket(this.containerId, status));
         }
      }
   }

   @Unique
   private static CraftingStatus thunderbolt$createStatus(IncrementalUpdateHelper changes, Ae2LtTimeWheelCraftingCpuLogic logic) {
      boolean full = changes.isFullUpdate();
      ArrayList<CraftingStatusEntry> entries = new ArrayList<>();

      for (AEKey what : changes) {
         long storedCount = logic.getStored(what);
         long activeCount = logic.getWaitingFor(what);
         long pendingCount = logic.getPendingOutputs(what);
         AEKey sentStack = what;
         if (!full && changes.getSerial(what) != null) {
            sentStack = null;
         }

         CraftingStatusEntry entry = new CraftingStatusEntry(changes.getOrAssignSerial(what), sentStack, storedCount, activeCount, pendingCount);
         entries.add(entry);
         if (entry.isDeleted()) {
            changes.removeSerial(what);
         }
      }

      ElapsedTimeTracker tracker = logic.getElapsedTimeTracker();
      long remaining = thunderbolt$remainingProgressUnits(tracker.getProgress());
      return new CraftingStatus(full, tracker.getElapsedTime(), remaining, 2147483647L, entries);
   }

   @Unique
   private static long thunderbolt$remainingProgressUnits(float progress) {
      return (long)(2.147483647E9 - (double)progress * 2.147483647E9);
   }
}
