package com.moakiee.thunderbolt.ae2.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.me.service.CraftingService;

import com.moakiee.thunderbolt.ae2.crafting.ExtendedCraftingCpuInsertBridge;

/**
 * Adds extended CPUs after the crafting-service storage has offered an insertion to AE2 CPUs.
 *
 * <p>The interception deliberately lives on AE2's storage caller rather than
 * {@code CraftingService.insertIntoCpus}. AdvancedAE 1.3.6 overwrites that method and Mixin 0.8.5
 * rejects every later injector on it during preparation. Wrapping the stable call site composes
 * with vanilla AE2, AdvancedAE's overwrite, and other addons without replacing any of them.
 */
@Mixin(targets = "appeng.me.service.helpers.CraftingServiceStorage$1", remap = false)
public abstract class ExtendedCraftingCpuInsertMixin {

    @WrapOperation(
            method = "insert",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/me/service/CraftingService;insertIntoCpus"
                            + "(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;)J"))
    private long thunderbolt$insertIntoAllCpuFamilies(
            CraftingService service,
            AEKey what,
            long amount,
            Actionable mode,
            Operation<Long> original) {
        long boundedAmount = Math.max(0L, amount);
        long inserted = Math.min(
                boundedAmount,
                Math.max(0L, original.call(service, what, amount, mode)));
        if (inserted >= boundedAmount
                || !(service instanceof ExtendedCraftingCpuInsertBridge bridge)) {
            return inserted;
        }

        long extended = bridge.thunderbolt$insertIntoExtendedCpus(
                what, boundedAmount - inserted, mode);
        long boundedExtended = Math.min(
                boundedAmount - inserted,
                Math.max(0L, extended));
        return inserted + boundedExtended;
    }
}
