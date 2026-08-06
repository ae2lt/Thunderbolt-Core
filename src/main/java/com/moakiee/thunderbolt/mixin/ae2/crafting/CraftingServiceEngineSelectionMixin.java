package com.moakiee.thunderbolt.mixin.ae2.crafting;

import java.util.concurrent.Future;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.Level;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.me.service.CraftingService;

import com.moakiee.thunderbolt.ThunderboltCore;
import com.moakiee.thunderbolt.api.crafting.engine.CraftingEngineRegistry;
import com.moakiee.thunderbolt.api.crafting.engine.CraftingEngineRequest;
import com.moakiee.thunderbolt.api.crafting.engine.CraftingEngineSelection;
import com.moakiee.thunderbolt.core.crafting.engine.PlayerEngineSelection;

/**
 * 第一层递归 mixin：与 vm（{@code order=100}）、eco（{@code order=500}）同一接缝
 * （{@code CraftingService#beginCraftingCalculation} HEAD）。以 {@code order=40} 最先执行：
 *
 * <ul>
 *   <li>读取已注册的附属名单（{@link CraftingEngineRegistry}）与当前选择
 *       （{@link CraftingEngineSelection}）；</li>
 *   <li>选中哪个引擎就路由到哪个；都没开（{@code none}）原路返回 AE2 原版；</li>
 *   <li>闪电（{@code thunderbolt}）被选中时返回原版计算逻辑并取消其余 mixin —— 这样第一层
 *       计算仍会落到闪电自己的深层规划器（{@code CraftingCalculationMixin}）。</li>
 * </ul>
 *
 * <p>该 mixin 只定义闪电侧的契约；第三方引擎（vm/eco）后续自行适配闪电库后即可被选择。
 */
@Mixin(value = CraftingService.class, remap = false)
public abstract class CraftingServiceEngineSelectionMixin {

    @Shadow
    @Final
    private IGrid grid;

    /** Set while a native (original) calculation is re-entered so we never recurse. */
    private static final ThreadLocal<Boolean> NATIVE_FALLBACK =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(method = "beginCraftingCalculation", at = @At("HEAD"), cancellable = true, order = 40)
    private void thunderbolt$selectEngine(
            Level level,
            ICraftingSimulationRequester simRequester,
            AEKey what,
            long amount,
            CalculationStrategy strategy,
            CallbackInfoReturnable<Future<ICraftingPlan>> cir) {
        if (NATIVE_FALLBACK.get() || cir.isCancelled()) {
            return;
        }

        // 按请求者解析有效引擎：玩家请求 → 玩家个人选择（未设置回落机器默认）；机器 → 机器默认。
        String machineDefault = CraftingEngineSelection.current();
        boolean playerRequest = PlayerEngineSelection.isPlayerRequest(simRequester);
        String selected = PlayerEngineSelection.resolve(simRequester, machineDefault);
        if (selected == null || CraftingEngineRegistry.NONE.equals(selected)) {
            // 都没开 → 原路返回 AE2 原版计算
            ThunderboltCore.LOGGER.info(
                    "[Thunderbolt Core][engine] vanilla ({}: none selected): requester={} what={} amount={}",
                    playerRequest ? "player" : "machine",
                    requesterName(simRequester), what, amount);
            return;
        }

        var engine = CraftingEngineRegistry.byId(selected).orElse(null);
        if (engine == null || !engine.isEnabled()) {
            ThunderboltCore.LOGGER.warn(
                    "[Thunderbolt Core][engine] vanilla ({}: '{}' unavailable): requester={} what={} amount={}",
                    playerRequest ? "player" : "machine",
                    selected, requesterName(simRequester), what, amount);
            return;
        }

        var request = new CraftingEngineRequest(
                level, grid, simRequester, what, amount, strategy,
                (lvl, req, w, amt, strat) -> {
                    NATIVE_FALLBACK.set(Boolean.TRUE);
                    try {
                        return ((CraftingService) (Object) this).beginCraftingCalculation(
                                lvl, req, w, amt, strat);
                    } finally {
                        NATIVE_FALLBACK.remove();
                    }
                });

        try {
            Future<ICraftingPlan> future = engine.route(request);
            if (future == null) {
                // engine declined → fall through to the original path
                ThunderboltCore.LOGGER.info(
                        "[Thunderbolt Core][engine] vanilla (engine '{}' declined): what={} amount={}",
                        selected, what, amount);
                return;
            }
            cir.setReturnValue(future);
            cir.cancel(); // 取消其它（未协作的）mixin：vm order=100、eco order=500
            ThunderboltCore.LOGGER.info(
                    "[Thunderbolt Core][engine] '{}' ({}) took over: requester={} what={} amount={} strategy={}",
                    selected, playerRequest ? "player" : "machine",
                    requesterName(simRequester), what, amount, strategy);
        } catch (Throwable t) {
            ThunderboltCore.LOGGER.warn(
                    "[Thunderbolt Core][engine] vanilla ({}: engine '{}' failed): requester={} what={} amount={}",
                    playerRequest ? "player" : "machine",
                    selected, requesterName(simRequester), what, amount, t);
        }
    }

    private static String requesterName(ICraftingSimulationRequester requester) {
        return requester == null ? "null" : requester.getClass().getSimpleName();
    }
}
