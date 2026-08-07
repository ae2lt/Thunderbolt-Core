package com.moakiee.thunderbolt.core.crafting.algorithm.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import appeng.client.gui.Icon;
import appeng.client.gui.implementations.AESubScreen;
import appeng.client.gui.widgets.TabButton;
import appeng.core.network.serverbound.SwitchGuisPacket;
import appeng.menu.implementations.PriorityMenu;

import com.moakiee.thunderbolt.core.crafting.algorithm.menu.CraftingAlgorithmProviderMenu;

/** Texture-free default editor intended to be opened as a secondary provider screen. */
public final class CraftingAlgorithmProviderScreen
        extends AbstractContainerScreen<CraftingAlgorithmProviderMenu> {
    private static final int PANEL_COLOR = 0xEECDD2E3;
    private static final int BORDER_DARK = 0xFF55596B;
    private static final int BORDER_LIGHT = 0xFFF4F5FA;
    private static final int TEXT = 0xFF20232C;
    private static final int MUTED = 0xFF555A6D;

    public CraftingAlgorithmProviderScreen(
            CraftingAlgorithmProviderMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 256;
        imageHeight = 106;
        titleLabelX = 10;
        titleLabelY = 8;
        inventoryLabelY = 10_000;
    }

    @Override
    protected void init() {
        super.init();
        int y = topPos + 42;
        addRenderableWidget(Button.builder(Component.literal("<"), ignored -> sendButton(
                CraftingAlgorithmProviderMenu.PREVIOUS_ALGORITHM))
                .bounds(leftPos + 10, y, 24, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), ignored -> sendButton(
                CraftingAlgorithmProviderMenu.NEXT_ALGORITHM))
                .bounds(leftPos + imageWidth - 34, y, 24, 20).build());

        var backLabel = menu.getHost().getMainMenuIcon().getHoverName();
        var back = new TabButton(Icon.BACK, backLabel, ignored -> AESubScreen.goBack());
        back.setTooltip(Tooltip.create(backLabel));
        back.setSize(20, 20);
        back.setPosition(leftPos + imageWidth - 24, topPos - 5);
        addRenderableWidget(back);

        var priorityLabel = Component.translatable(
                "gui.thunderbolt.algorithm_provider.selection_priority");
        var priority = new TabButton(
                Icon.PRIORITY,
                priorityLabel,
                ignored -> PacketDistributor.sendToServer(
                        SwitchGuisPacket.openSubMenu(PriorityMenu.TYPE)));
        priority.setTooltip(Tooltip.create(priorityLabel));
        priority.setSize(20, 20);
        priority.setPosition(leftPos + imageWidth - 46, topPos - 5);
        addRenderableWidget(priority);
    }

    private void sendButton(int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, PANEL_COLOR);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + 1, BORDER_DARK);
        graphics.fill(leftPos, topPos, leftPos + 1, topPos + imageHeight, BORDER_DARK);
        graphics.fill(leftPos, topPos + imageHeight - 1,
                leftPos + imageWidth, topPos + imageHeight, BORDER_LIGHT);
        graphics.fill(leftPos + imageWidth - 1, topPos,
                leftPos + imageWidth, topPos + imageHeight, BORDER_LIGHT);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, TEXT, false);
        graphics.drawString(font,
                Component.translatable("gui.thunderbolt.algorithm_provider.algorithm"),
                10, 27, MUTED, false);
        drawCenteredString(graphics, menu.selectedAlgorithmName(), 47, TEXT);
        drawCenteredString(graphics,
                Component.translatable(menu.selectedAlgorithmIsPublic()
                        ? "gui.thunderbolt.algorithm_provider.public"
                        : "gui.thunderbolt.algorithm_provider.provider_required"),
                64, MUTED);
        Component algorithmPriority;
        if (menu.selectedAlgorithmIsVanilla()) {
            algorithmPriority = Component.translatable(
                    "gui.thunderbolt.algorithm_provider.algorithm_priority.vanilla");
        } else if (menu.selectedAlgorithmIsKnown()) {
            algorithmPriority = Component.translatable(
                    "gui.thunderbolt.algorithm_provider.algorithm_priority",
                    menu.selectedAlgorithmPriority());
        } else {
            algorithmPriority = Component.translatable(
                    "gui.thunderbolt.algorithm_provider.algorithm_priority.unknown");
        }
        drawCenteredString(graphics, algorithmPriority, 77, TEXT);
        drawCenteredString(graphics,
                Component.translatable(
                        "gui.thunderbolt.algorithm_provider.player_priority", menu.priority()),
                88, TEXT);
    }

    private void drawCenteredString(
            GuiGraphics graphics, Component text, int y, int color) {
        graphics.drawString(font, text, (imageWidth - font.width(text)) / 2, y, color, false);
    }
}
