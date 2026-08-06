package com.moakiee.thunderbolt.core.crafting.engine.client;

// [Thunderbolt-Core] engine-selection + mixin-package-fixes changeset (PR -> refactor/thunderbolt-three-layer-clean, 2026-08-07)

import net.minecraft.network.chat.Component;

import appeng.client.gui.Icon;
import appeng.client.gui.widgets.IconButton;

public final class EngineSelectionButton extends IconButton {

    public EngineSelectionButton(OnPress onPress) {
        super(onPress);
        setMessage(Component.literal("AE2 合成计算引擎"));
    }

    @Override
    protected Icon getIcon() {
        return Icon.COG;
    }
}
