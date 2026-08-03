package com.moakiee.thunderbolt.ae2.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.menu.me.crafting.CraftingPlanSummary;

import com.moakiee.thunderbolt.ae2.crafting.CraftingPlanSummaryAdapter;

/**
 * Keeps AE2: Crafting Tree's confirmation-summary hook compatible with custom plans.
 *
 * <p>AE2CT declares its hook parameter as {@link ICraftingPlan}, but then unconditionally casts it
 * to AE2's final {@code CraftingPlan} record. Third-party plans cannot inherit from that record.
 * This mixin substitutes a concrete, isolated snapshot only for this summary call. The confirmation
 * menu and CPU submission retain the original plan and all of its private execution metadata.
 */
@Mixin(value = CraftingPlanSummary.class, priority = 2000, remap = false)
public abstract class Ae2CraftingTreeCompatibilityMixin {
    @ModifyVariable(method = "fromJob", at = @At("HEAD"), argsOnly = true, index = 2)
    private static ICraftingPlan thunderbolt$adaptPlanForAe2CraftingTree(ICraftingPlan job) {
        return CraftingPlanSummaryAdapter.adapt(job);
    }
}
