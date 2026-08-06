package com.moakiee.thunderbolt.core.crafting.engine.client;

// [Thunderbolt-Core] engine-selection + mixin-package-fixes changeset (PR -> refactor/thunderbolt-three-layer-clean, 2026-08-07)

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.AECheckbox;
import appeng.client.gui.widgets.AE2Button;

import com.moakiee.thunderbolt.api.crafting.engine.CraftingEngineRegistry;
import com.moakiee.thunderbolt.api.crafting.engine.CraftingEngineSelection;
import com.moakiee.thunderbolt.core.crafting.engine.net.CraftingEngineNetwork;

public class EngineSelectionScreen extends AEBaseScreen<EngineSelectionMenu> {

    private static final int ROW_LEFT = 20;
    private static final int ROW_HEIGHT = 16;
    private static final int ROW_SPACING = 6;
    private static final int HEADER_OFFSET = 26;

    private final Screen previous;
    private final List<Option> options = new ArrayList<>();
    private String selectedId;

    private record Option(AECheckbox checkbox, String id) {
    }

    public EngineSelectionScreen(EngineSelectionMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title,
                StyleManager.loadStyleDoc("/screens/thunderbolt_engine_selection.json"));
        this.previous = Minecraft.getInstance().screen;
        this.selectedId = CraftingEngineSelection.current();
    }

    /** 从侧边按钮打开：构造纯客户端幽灵菜单 + AE2 屏幕。 */
    public static void open() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        var menu = new EngineSelectionMenu(0, mc.player.getInventory());
        mc.setScreen(new EngineSelectionScreen(
                menu, mc.player.getInventory(), Component.literal("AE2 合成计算引擎")));
    }

    @Override
    protected void init() {
        super.init();
        this.options.clear();

        int y = this.topPos + HEADER_OFFSET;
        addOption(CraftingEngineRegistry.NONE, "原版计算", y);
        y += ROW_HEIGHT + ROW_SPACING;
        for (var engine : CraftingEngineRegistry.available()) {
            addOption(engine.id(), engine.displayName() + " 快速规划", y);
            y += ROW_HEIGHT + ROW_SPACING;
        }
        refreshRadios();
        addBottomButtons(y + 6);
    }

    /** AE2 原版最基础组件：底部「返回」「退出」按钮。 */
    private void addBottomButtons(int buttonY) {
        int panelCenter = this.leftPos + this.imageWidth / 2;
        this.addRenderableWidget(new AE2Button(
                panelCenter - 74, buttonY, 70, 20,
                Component.literal("返回"), btn -> back()));
        this.addRenderableWidget(new AE2Button(
                panelCenter + 4, buttonY, 70, 20,
                Component.literal("退出"), btn -> exit()));
    }

    /** 返回上一个界面（底层 AE2 终端）。 */
    private void back() {
        Minecraft.getInstance().setScreen(this.previous);
    }

    /** 退出弹窗，回到游戏。 */
    private void exit() {
        Minecraft.getInstance().setScreen(null);
    }

    private void addOption(String id, String label, int y) {
        var checkbox = new AECheckbox(
                this.leftPos + ROW_LEFT, y, this.imageWidth - 2 * ROW_LEFT, ROW_HEIGHT,
                this.style, Component.literal(label));
        checkbox.setRadio(true);
        checkbox.setChangeListener(() -> onToggle(id, checkbox));
        this.addRenderableWidget(checkbox);
        this.options.add(new Option(checkbox, id));
    }

    private void refreshRadios() {
        for (var option : this.options) {
            option.checkbox().setSelected(option.id().equals(this.selectedId));
        }
    }

    private void onToggle(String id, AECheckbox clicked) {
        String next = clicked.isSelected() ? id : CraftingEngineRegistry.NONE;
        this.selectedId = next;
        this.refreshRadios();
        CraftingEngineNetwork.sendToServer(next);
    }

    @Override
    public void onClose() {
        // 纯客户端幽灵菜单：不发送容器关闭包（避免关掉底层 AE2 界面），直接回到上一个界面。
        Minecraft.getInstance().setScreen(this.previous);
    }

    @Override
    protected boolean shouldAddToolbar() {
        // 引擎选择弹窗自身不需要侧边按钮栏（也不显示帮助/引擎按钮）。
        return false;
    }
}
