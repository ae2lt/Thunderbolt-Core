package com.moakiee.thunderbolt.core.crafting.engine.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.StringWidget;
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
    private boolean globalScope; // true = 全局机器（配置文件），false = 我的引擎（玩家 NBT）
    private AECheckbox globalScopeBox;
    private AECheckbox playerScopeBox;
    private StringWidget footerWidget;

    private record Option(AECheckbox checkbox, String id) {
    }

    public EngineSelectionScreen(EngineSelectionMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title,
                StyleManager.loadStyleDoc("/screens/thunderbolt_engine_selection.json"));
        this.previous = Minecraft.getInstance().screen;
        this.globalScope = false;
    }

    /** 从侧边按钮打开：构造纯客户端幽灵菜单 + AE2 屏幕。 */
    public static void open() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        var menu = new EngineSelectionMenu(0, mc.player.getInventory());
        mc.setScreen(new EngineSelectionScreen(
                menu, mc.player.getInventory(), Component.literal("合成计算引擎")));
    }

    @Override
    protected void init() {
        super.init();
        this.options.clear();

        int y = this.topPos + HEADER_OFFSET;
        addScopeSelector(y);
        y += ROW_HEIGHT + ROW_SPACING;
        addOption(CraftingEngineRegistry.NONE, "原版计算", y);
        y += ROW_HEIGHT + ROW_SPACING;
        for (var engine : CraftingEngineRegistry.available()) {
            addOption(engine.id(), engine.displayName() + " 快速规划", y);
            y += ROW_HEIGHT + ROW_SPACING;
        }
        refreshScopeRadios();
        refreshRadios();
        addBottomButtons(y + 6);
        refreshFooter();
    }

    /** 底部说明行：机器默认路由（配置文件）与当前个人选择。 */
    private void refreshFooter() {
        if (this.footerWidget != null) {
            this.removeWidget(this.footerWidget);
        }
        String footer = "全局机器: " + CraftingEngineSelection.current()
                + "　我的引擎: " + CraftingEngineSelection.playerCurrent();
        Component footerText = Component.literal(footer);
        int textWidth = this.font.width(footerText);
        this.footerWidget = new StringWidget(
                this.leftPos + (this.imageWidth - textWidth) / 2,
                this.topPos + this.imageHeight - 12,
                textWidth, 9, footerText, this.font);
        this.footerWidget.setColor(0xFF8A8F9B);
        this.addRenderableWidget(this.footerWidget);
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

    /** 顶部作用域单选：全局机器（配置文件）还是我的引擎（玩家 NBT）。 */
    private void addScopeSelector(int y) {
        this.globalScopeBox = new AECheckbox(
                this.leftPos + ROW_LEFT, y, 90, ROW_HEIGHT, this.style,
                Component.literal("全局机器"));
        this.globalScopeBox.setRadio(true);
        this.globalScopeBox.setChangeListener(() -> onScopeChange(true, this.globalScopeBox));
        this.addRenderableWidget(this.globalScopeBox);

        this.playerScopeBox = new AECheckbox(
                this.leftPos + ROW_LEFT + 96, y, 90, ROW_HEIGHT, this.style,
                Component.literal("我的引擎"));
        this.playerScopeBox.setRadio(true);
        this.playerScopeBox.setChangeListener(() -> onScopeChange(false, this.playerScopeBox));
        this.addRenderableWidget(this.playerScopeBox);
    }

    private void onScopeChange(boolean global, AECheckbox clicked) {
        if (clicked.isSelected()) {
            this.globalScope = global;
        }
        this.refreshScopeRadios();
        this.refreshRadios();
    }

    private void refreshScopeRadios() {
        this.globalScopeBox.setSelected(this.globalScope);
        this.playerScopeBox.setSelected(!this.globalScope);
    }

    /** 当前作用域高亮哪个引擎：全局机器=配置文件值，我的引擎=玩家个人选择。 */
    private String activeEngineId() {
        return this.globalScope
                ? CraftingEngineSelection.current()
                : CraftingEngineSelection.playerCurrent();
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
        String active = activeEngineId();
        for (var option : this.options) {
            option.checkbox().setSelected(option.id().equals(active));
        }
    }

    private void onToggle(String id, AECheckbox clicked) {
        String next = clicked.isSelected() ? id : CraftingEngineRegistry.NONE;
        if (this.globalScope) {
            CraftingEngineNetwork.sendToServerGlobal(next);
            CraftingEngineSelection.seed(next); // 本地即时反馈（服务端回包会覆盖）
        } else {
            CraftingEngineNetwork.sendToServer(next);
            CraftingEngineSelection.seedPlayer(next); // 本地即时反馈
        }
        this.refreshRadios();
        this.refreshFooter();
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
