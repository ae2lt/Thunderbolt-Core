package com.moakiee.thunderbolt.ae2.mixin;

import java.util.ArrayList;
import java.util.Collection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingTreeNode;

import com.moakiee.thunderbolt.api.probability.ProbabilityPatternApi;

/**
 * Adapts AE2's native crafting tree for addon-registered pattern adapters via the
 * public {@link ProbabilityPatternApi#adaptPatternForRequest} API.
 * <p>
 * Registered {@link com.moakiee.thunderbolt.api.probability.PatternRequestAdapter}s (e.g.
 * Probability-Pattern's statistical scaling) handle the actual wrapping, keeping
 * this mixin addon-agnostic.
 */
@Mixin(CraftingTreeNode.class)
public abstract class CraftingTreeNodePatternAdapterMixin {

    @Shadow
    private long amount;

    @Unique
    private long thunderbolt$adaptedRequestAmount;

    /**
     * Capture requestedAmount just before {@code buildChildPatterns()} runs.
     * At this point requestedAmount has been reduced by any items extracted from
     * inventory, so it reflects the amount that actually needs crafting.
     */
    @ModifyVariable(
            method = "request",
            at = @At(value = "INVOKE",
                    target = "Lappeng/crafting/CraftingTreeNode;buildChildPatterns()V"),
            argsOnly = true)
    private long thunderbolt$captureRequestAmount(long requestedAmount) {
        this.thunderbolt$adaptedRequestAmount = requestedAmount * this.amount;
        return requestedAmount;
    }

    @Redirect(
            method = "buildChildPatterns",
            at = @At(value = "INVOKE",
                    target = "Lappeng/api/networking/crafting/ICraftingService;getCraftingFor(Lappeng/api/stacks/AEKey;)Ljava/util/Collection;"))
    private Collection<IPatternDetails> thunderbolt$adaptPatternsForRequest(ICraftingService service,
            AEKey whatToCraft) {
        Collection<IPatternDetails> patterns = service.getCraftingFor(whatToCraft);
        if (this.thunderbolt$adaptedRequestAmount <= 0) {
            return patterns;
        }
        var adapted = new ArrayList<IPatternDetails>(patterns.size());
        long amount = Math.max(1L, this.thunderbolt$adaptedRequestAmount);
        for (var p : patterns) {
            adapted.add(ProbabilityPatternApi.adaptPatternForRequest(p, amount));
        }
        return adapted;
    }
}
