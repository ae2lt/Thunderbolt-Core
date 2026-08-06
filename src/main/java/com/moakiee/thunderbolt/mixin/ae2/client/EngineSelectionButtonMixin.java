package com.moakiee.thunderbolt.mixin.ae2.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.components.Button;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.common.MEStorageScreen;

import com.moakiee.thunderbolt.core.crafting.engine.client.EngineSelectionButton;
import com.moakiee.thunderbolt.core.crafting.engine.client.EngineSelectionScreen;

/**
 * 在 AE2 <b>终端类</b>界面（{@link MEStorageScreen} 及其子类：ME 终端 / 合成终端 / 样板终端）
 * 的左侧加入同风格引擎选择按钮。
 *
 * <p>按钮在构造器尾部通过 {@code addToLeftToolbar}（AE2 原版 help 按钮同一位置机制）加入
 * 垂直按钮栏，点击弹出 {@link EngineSelectionScreen}。非终端类界面（如 Pattern Access、
 * 各类机器）不加按钮。
 *
 * <p>注意：这里用 <b>方法</b> shadow 而非字段 shadow —— 对目标类的 {@code private final}
 * 字段做 {@code @Shadow}（带 {@code = null} 初始化器）会干扰目标字段的构造期赋值，导致
 * {@code WidgetContainer.add} 收到 null widget 而 NPE。
 */
@Mixin(value = AEBaseScreen.class, remap = false)
public abstract class EngineSelectionButtonMixin {

    @Shadow
    protected abstract <B extends Button> B addToLeftToolbar(B button);

    @Inject(
            method = "<init>(Lappeng/menu/AEBaseMenu;Lnet/minecraft/world/entity/player/Inventory;"
                    + "Lnet/minecraft/network/chat/Component;Lappeng/client/gui/style/ScreenStyle;)V",
            at = @At("TAIL"))
    private void thunderbolt$addEngineSelectionButton(CallbackInfo ci) {
        // 只给终端类界面（MEStorageScreen 及其子类）加齿轮按钮；此时 this 已是最终子类实例。
        if (!((Object) this instanceof MEStorageScreen)) {
            return;
        }
        addToLeftToolbar(new EngineSelectionButton(btn -> EngineSelectionScreen.open()));
    }
}
