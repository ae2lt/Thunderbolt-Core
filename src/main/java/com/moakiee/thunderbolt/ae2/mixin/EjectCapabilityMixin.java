package com.moakiee.thunderbolt.ae2.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;

import com.moakiee.thunderbolt.api.eject.EjectCapabilityRegistry;

// Forge 1.20.1 port: intercept BlockEntity#getCapability(Capability, Direction) directly.
// Runs before third-party capability interceptors. Once a registered Thunderbolt EJECT endpoint
// supplies a result, the cancellable HEAD injection returns from getCapability immediately and
// lower-priority interceptors cannot replace ownership of that endpoint.
@Mixin(value = BlockEntity.class, priority = 2000, remap = false)
public abstract class EjectCapabilityMixin {
    // Forge 1.20.1: BlockEntity has concrete getLevel()/getBlockPos(); @Shadow declarations
    // make them resolvable inside the mixin. remap=false means the release-jar SRG names
    // (m_58904_ = getLevel, m_58899_ = getBlockPos) must be used.
    @Shadow
    public abstract Level m_58904_();

    @Shadow
    public abstract BlockPos m_58899_();

    @Unique
    private static final ThreadLocal<Boolean> THUNDERBOLT_PROXYING =
            ThreadLocal.withInitial(() -> false);

    @Unique
    private static final IItemHandler THUNDERBOLT_REJECTING_ITEM_HANDLER = new IItemHandler() {
        @Override public int getSlots() { return 1; }
        @Override public ItemStack getStackInSlot(int slot) { return ItemStack.EMPTY; }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { return ItemStack.EMPTY; }
        @Override public int getSlotLimit(int slot) { return 0; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return false; }
    };

    @Unique
    private static final IFluidHandler THUNDERBOLT_REJECTING_FLUID_HANDLER = new IFluidHandler() {
        @Override public int getTanks() { return 1; }
        @Override public FluidStack getFluidInTank(int tank) { return FluidStack.EMPTY; }
        @Override public int getTankCapacity(int tank) { return 0; }
        @Override public boolean isFluidValid(int tank, FluidStack stack) { return false; }
        @Override public int fill(FluidStack resource, FluidAction action) { return 0; }
        @Override public FluidStack drain(FluidStack resource, FluidAction action) { return FluidStack.EMPTY; }
        @Override public FluidStack drain(int maxDrain, FluidAction action) { return FluidStack.EMPTY; }
    };

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(method = "getCapability(Lnet/minecraftforge/common/capabilities/Capability;Lnet/minecraft/core/Direction;)Lnet/minecraftforge/common/util/LazyOptional;", at = @At("HEAD"), cancellable = true)
    private <T> void thunderbolt$interceptEjectCapability(
            Capability<T> capability,
            Direction side,
            CallbackInfoReturnable<LazyOptional<T>> callback) {
        // Registry-empty is the overwhelmingly common case on this global hot path; check it
        // before paying the ThreadLocal lookup.
        if (EjectCapabilityRegistry.isEmpty()
                || THUNDERBOLT_PROXYING.get()
                || EjectCapabilityRegistry.isBypassed()
                || !(this.m_58904_() instanceof ServerLevel)
                || side == null) {
            return;
        }

        var entry = EjectCapabilityRegistry.lookupByFace(this.m_58904_().dimension(), this.m_58899_().asLong(), side);
        if (entry == null) return;
        var host = entry.getHost();
        if (host == null) {
            // Forge 1.20.1: ForgeCapabilities has no *_BLOCK variants (those are 1.20.2+);
            // ITEM_HANDLER/FLUID_HANDLER are the capability tokens for block entities here.
            if (capability == ForgeCapabilities.ITEM_HANDLER) {
                callback.setReturnValue(LazyOptional.of(() -> THUNDERBOLT_REJECTING_ITEM_HANDLER).cast());
            } else if (capability == ForgeCapabilities.FLUID_HANDLER) {
                callback.setReturnValue(LazyOptional.of(() -> THUNDERBOLT_REJECTING_FLUID_HANDLER).cast());
            }
            return;
        }

        var hostLevel = host.getLevel();
        if (hostLevel == null) return;
        var hostPos = host.getBlockPos();
        THUNDERBOLT_PROXYING.set(true);
        try {
            var result = host.getCapability((Capability) capability, side);
            if (result.isPresent()) callback.setReturnValue((LazyOptional<T>) result);
        } finally {
            THUNDERBOLT_PROXYING.remove();
        }
    }
}
