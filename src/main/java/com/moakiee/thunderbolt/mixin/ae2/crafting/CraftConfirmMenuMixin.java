package com.moakiee.thunderbolt.mixin.ae2.crafting;

import java.util.concurrent.Future;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.menu.guisync.GuiSync;
import appeng.menu.me.crafting.CraftConfirmMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngines;
import com.moakiee.thunderbolt.core.crafting.algorithm.CraftingAlgorithmCalculationStatus;
import com.moakiee.thunderbolt.core.crafting.algorithm.menu.CraftingAlgorithmNameMenu;

/** Synchronizes the actually selected planning algorithm to AE2's confirmation screen. */
@Mixin(value = CraftConfirmMenu.class, remap = false)
public abstract class CraftConfirmMenuMixin implements CraftingAlgorithmNameMenu {
    @Shadow
    private Future<ICraftingPlan> job;

    @Unique
    @GuiSync(30_000)
    public Component thunderbolt$craftingAlgorithmName = Component.empty();

    @Inject(method = "planJob", at = @At("HEAD"))
    private void thunderbolt$resetAlgorithmName(
            AEKey what, int amount, CalculationStrategy strategy,
            CallbackInfoReturnable<Boolean> cir) {
        CraftingAlgorithmCalculationStatus.forget(job);
        thunderbolt$craftingAlgorithmName = Component.empty();
    }

    @Redirect(
            method = "planJob",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/api/networking/crafting/ICraftingService;"
                            + "beginCraftingCalculation(Lnet/minecraft/world/level/Level;"
                            + "Lappeng/api/networking/crafting/ICraftingSimulationRequester;"
                            + "Lappeng/api/stacks/AEKey;J"
                            + "Lappeng/api/networking/crafting/CalculationStrategy;)"
                            + "Ljava/util/concurrent/Future;"))
    private Future<ICraftingPlan> thunderbolt$trackCalculation(
            ICraftingService service,
            Level level,
            ICraftingSimulationRequester requester,
            AEKey what,
            long amount,
            CalculationStrategy strategy) {
        return CraftingAlgorithmCalculationStatus.track(
                requester,
                () -> service.beginCraftingCalculation(
                        level, requester, what, amount, strategy));
    }

    @Inject(method = "broadcastChanges", at = @At("HEAD"), remap = true)
    private void thunderbolt$syncAlgorithmName(CallbackInfo ci) {
        var selected = CraftingAlgorithmCalculationStatus.selected(job);
        if (selected != null) {
            thunderbolt$craftingAlgorithmName = CraftingPlanningEngines.getName(selected);
        }
    }

    @Inject(method = "removed", at = @At("HEAD"), remap = true)
    private void thunderbolt$forgetCalculation(Player player, CallbackInfo ci) {
        CraftingAlgorithmCalculationStatus.forget(job);
    }

    @Override
    public Component thunderbolt$getCraftingAlgorithmName() {
        return thunderbolt$craftingAlgorithmName;
    }
}
