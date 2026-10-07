package com.meakaandre.siftec.portal;

import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.place.Places;
import com.meakaandre.siftec.registry.ModBlocks;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The portal window: the company's other portals. Click one to go there. Run by the server. */
public class PortalMenu extends ChestMenu {
    private static final int SIZE = 54;
    private final ServerPlayer player;
    private final List<Places.Place> shown = new ArrayList<>();

    private PortalMenu(int id, Inventory inventory, SimpleContainer view, ServerPlayer player, Company company, BlockPos here) {
        super(MenuType.GENERIC_9x6, id, inventory, view, 6);
        this.player = player;
        for (Places.Place place : Places.of(player.level().getServer(), company.id)) {
            if (!place.portal() || Places.same(place, player.level(), here) || shown.size() >= SIZE) continue;
            boolean main = place.kind.equals(Places.MAIN_PORTAL);
            ItemStack icon = new ItemStack((main ? ModBlocks.MAIN_PORTAL.get() : ModBlocks.SATELLITE_PORTAL.get()).asItem());
            icon.set(DataComponents.CUSTOM_NAME, Component.literal(place.name).withStyle(s -> s.withItalic(false)));
            icon.set(DataComponents.LORE, new ItemLore(List.of(
                Component.translatable("siftec.portal.where", place.x, place.y, place.z, place.dimension).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY)),
                Component.translatable("siftec.portal.go").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.YELLOW)))));
            view.setItem(shown.size(), icon);
            shown.add(place);
        }
    }

    public static void open(ServerPlayer player, Company company, BlockPos here) {
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new PortalMenu(id, inventory, new SimpleContainer(SIZE), player, company, here),
            Component.translatable("siftec.portal.title")));
    }

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player who) {
        if (slot >= 0 && slot < SIZE) {
            if ((input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE) && slot < shown.size()) {
                Places.Place place = shown.get(slot);
                ServerLevel level = Places.level(player.level().getServer(), place);
                player.closeContainer();
                if (level == null || !(level.getBlockState(place.pos()).getBlock() instanceof PortalBlock)) {
                    player.sendOverlayMessage(Component.translatable("siftec.portal.gone"));
                } else if (level.getBlockState(place.pos().above()).isSuffocating(level, place.pos().above()) || level.getBlockState(place.pos().above(2)).isSuffocating(level, place.pos().above(2))) {
                    player.sendOverlayMessage(Component.translatable("siftec.portal.blocked"));
                } else player.teleportTo(level, place.x + 0.5, place.y + 1.0, place.z + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
            }
            return;
        }
        if (input == ContainerInput.PICKUP || input == ContainerInput.THROW) super.clicked(slot, button, input, who);
    }

    @Override
    public ItemStack quickMoveStack(Player who, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player who) {
        return true;
    }
}
