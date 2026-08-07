package com.moakiee.thunderbolt.core.crafting.algorithm.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

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
        imageHeight = 126;
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

        int priorityY = topPos + 88;
        addPriorityButton(leftPos + 10, priorityY, "-10",
                CraftingAlgorithmProviderMenu.PRIORITY_MINUS_TEN);
        addPriorityButton(leftPos + 55, priorityY, "-1",
                CraftingAlgorithmProviderMenu.PRIORITY_MINUS_ONE);
        addPriorityButton(leftPos + 100, priorityY, "0",
                CraftingAlgorithmProviderMenu.PRIORITY_RESET);
        addPriorityButton(leftPos + 145, priorityY, "+1",
                CraftingAlgorithmProviderMenu.PRIORITY_PLUS_ONE);
        addPriorityButton(leftPos + 190, priorityY, "+10",
                CraftingAlgorithmProviderMenu.PRIORITY_PLUS_TEN);
    }

    private void addPriorityButton(int x, int y, String text, int id) {
        addRenderableWidget(Button.builder(Component.literal(text), ignored -> sendButton(id))
                .bounds(x, y, 40, 20).build());
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
        var id = menu.selectedAlgorithm().toString();
        graphics.drawCenteredString(font, id, imageWidth / 2, 47, TEXT);
        graphics.drawCenteredString(font,
                Component.translatable(menu.selectedAlgorithmIsPublic()
                        ? "gui.thunderbolt.algorithm_provider.public"
                        : "gui.thunderbolt.algorithm_provider.provider_required"),
                imageWidth / 2, 64, MUTED);
        graphics.drawCenteredString(font,
                Component.translatable(
                        "gui.thunderbolt.algorithm_provider.priority", menu.priority()),
                imageWidth / 2, 77, TEXT);
    }
}
