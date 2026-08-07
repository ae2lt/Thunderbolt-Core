package com.moakiee.thunderbolt.core.crafting.algorithm.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;

import com.moakiee.thunderbolt.api.crafting.ConfigurableCraftingAlgorithmProvider;
import com.moakiee.thunderbolt.api.crafting.CraftingAlgorithmSelection;
import com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngines;
import com.moakiee.thunderbolt.core.crafting.algorithm.ThunderboltMenus;

/** Default server-authoritative submenu for a configurable algorithm provider. */
public final class CraftingAlgorithmProviderMenu extends AbstractContainerMenu {
    public static final int PREVIOUS_ALGORITHM = 0;
    public static final int NEXT_ALGORITHM = 1;
    public static final int PRIORITY_MINUS_TEN = 2;
    public static final int PRIORITY_MINUS_ONE = 3;
    public static final int PRIORITY_RESET = 4;
    public static final int PRIORITY_PLUS_ONE = 5;
    public static final int PRIORITY_PLUS_TEN = 6;

    public static final int MIN_PRIORITY = -1_000_000;
    public static final int MAX_PRIORITY = 1_000_000;
    private static final int MAX_ALGORITHMS = 256;

    private final List<ResourceLocation> algorithms;
    @Nullable
    private final ConfigurableCraftingAlgorithmProvider provider;
    private final Predicate<Player> validity;
    private int selectedIndex;
    private int priority;

    private CraftingAlgorithmProviderMenu(
            int id,
            List<ResourceLocation> algorithms,
            int selectedIndex,
            int priority,
            @Nullable ConfigurableCraftingAlgorithmProvider provider,
            Predicate<Player> validity) {
        super(ThunderboltMenus.CRAFTING_ALGORITHM_PROVIDER.get(), id);
        if (algorithms.isEmpty() || algorithms.size() > MAX_ALGORITHMS) {
            throw new IllegalArgumentException("Invalid crafting algorithm list size " + algorithms.size());
        }
        this.algorithms = List.copyOf(algorithms);
        this.selectedIndex = Math.floorMod(selectedIndex, algorithms.size());
        this.priority = clampPriority(priority);
        this.provider = provider;
        this.validity = Objects.requireNonNull(validity, "validity");
        addDataSlot(new DataSlot() {
            @Override
            public int get() {
                return CraftingAlgorithmProviderMenu.this.selectedIndex;
            }

            @Override
            public void set(int value) {
                CraftingAlgorithmProviderMenu.this.selectedIndex = Math.floorMod(value, algorithms.size());
            }
        });
        addDataSlot(new DataSlot() {
            @Override
            public int get() {
                return CraftingAlgorithmProviderMenu.this.priority;
            }

            @Override
            public void set(int value) {
                CraftingAlgorithmProviderMenu.this.priority = clampPriority(value);
            }
        });
    }

    public static CraftingAlgorithmProviderMenu clientCreate(
            int id, Inventory inventory, FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count <= 0 || count > MAX_ALGORITHMS) {
            throw new IllegalArgumentException("Invalid crafting algorithm count " + count);
        }
        var algorithms = new ArrayList<ResourceLocation>(count);
        for (int i = 0; i < count; i++) {
            algorithms.add(buf.readResourceLocation());
        }
        return new CraftingAlgorithmProviderMenu(
                id, algorithms, buf.readVarInt(), buf.readInt(), null, ignored -> true);
    }

    /**
     * Opens the reusable submenu. The host supplies its own distance/security predicate; settings
     * are applied immediately on the server through {@link #clickMenuButton}.
     */
    public static void open(
            ServerPlayer player,
            ConfigurableCraftingAlgorithmProvider provider,
            Predicate<Player> validity) {
        open(player, provider,
                Component.translatable("gui.thunderbolt.algorithm_provider.title"), validity);
    }

    public static void open(
            ServerPlayer player,
            ConfigurableCraftingAlgorithmProvider provider,
            Component title,
            Predicate<Player> validity) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(validity, "validity");

        var current = provider.snapshot();
        var algorithms = new ArrayList<>(CraftingPlanningEngines.allIds());
        if (!algorithms.contains(current.algorithmId())) {
            // Preserve an unknown ID loaded from NBT so installing its mod later restores it.
            algorithms.addFirst(current.algorithmId());
        }
        int selectedIndex = algorithms.indexOf(current.algorithmId());
        var menuProvider = new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return title;
            }

            @Override
            public AbstractContainerMenu createMenu(
                    int id, Inventory inventory, Player ignored) {
                return new CraftingAlgorithmProviderMenu(
                        id, algorithms, selectedIndex, current.priority(), provider, validity);
            }
        };
        player.openMenu(menuProvider, buf -> {
            buf.writeVarInt(algorithms.size());
            algorithms.forEach(buf::writeResourceLocation);
            buf.writeVarInt(selectedIndex);
            buf.writeInt(current.priority());
        });
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (provider == null || !validity.test(player)) {
            return false;
        }
        switch (id) {
            case PREVIOUS_ALGORITHM -> selectedIndex = Math.floorMod(selectedIndex - 1, algorithms.size());
            case NEXT_ALGORITHM -> selectedIndex = Math.floorMod(selectedIndex + 1, algorithms.size());
            case PRIORITY_MINUS_TEN -> priority = clampPriority(priority - 10L);
            case PRIORITY_MINUS_ONE -> priority = clampPriority(priority - 1L);
            case PRIORITY_RESET -> priority = 0;
            case PRIORITY_PLUS_ONE -> priority = clampPriority(priority + 1L);
            case PRIORITY_PLUS_TEN -> priority = clampPriority(priority + 10L);
            default -> {
                return false;
            }
        }
        provider.setSelection(new CraftingAlgorithmSelection(selectedAlgorithm(), priority));
        broadcastChanges();
        return true;
    }

    public ResourceLocation selectedAlgorithm() {
        return algorithms.get(selectedIndex);
    }

    public int priority() {
        return priority;
    }

    public boolean selectedAlgorithmIsPublic() {
        return CraftingPlanningEngines.isPublic(selectedAlgorithm());
    }

    @Override
    public boolean stillValid(Player player) {
        return provider == null || validity.test(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    private static int clampPriority(long value) {
        return (int) Math.max(MIN_PRIORITY, Math.min(MAX_PRIORITY, value));
    }
}
