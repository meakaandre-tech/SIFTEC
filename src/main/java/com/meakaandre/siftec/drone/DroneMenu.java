package com.meakaandre.siftec.drone;

import com.meakaandre.siftec.building.BuildingMenus;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.depot.DepotMenu;
import com.meakaandre.siftec.place.Places;
import com.meakaandre.siftec.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

/** The Drone Port's window: the company's other ports to pick a destination from, and the port's state. Run by the server. */
public class DroneMenu extends ChestMenu {
    private static final int SIZE = 54, LIST = 45, STATUS = 45, DRONE = 47, COLLECT = 49, FUEL = 51;
    private final SimpleContainer view;
    private final ServerPlayer player;
    private final DronePortBlockEntity port;
    /** Where it was opened (the terminal, or the building's controller): the player must stay within reach of it. */
    private final BlockPos at;
    private final List<Places.Place> shown = new ArrayList<>();
    private int ticks;

    private DroneMenu(int id, Inventory inventory, SimpleContainer view, ServerPlayer player, DronePortBlockEntity port, BlockPos at) {
        super(MenuType.GENERIC_9x6, id, inventory, view, 6);
        this.view = view;
        this.player = player;
        this.port = port;
        this.at = at.immutable();
        refresh();
    }

    /** Opens a port's window; {@code at} is the block it was opened at, which the player must stay within reach of. */
    public static void open(ServerPlayer player, DronePortBlockEntity port, BlockPos at) {
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new DroneMenu(id, inventory, new SimpleContainer(SIZE), player, port, at),
            Component.translatable("block.siftec.drone_port")));
    }

    static ItemStack button(Item icon, Component name, List<Component> lore, boolean glint) {
        ItemStack stack = new ItemStack(icon);
        stack.set(DataComponents.CUSTOM_NAME, Component.empty().append(name).withStyle(s -> s.withItalic(false)));
        List<Component> lines = new ArrayList<>();
        for (Component line : lore) lines.add(Component.empty().append(line).withStyle(s -> s.withItalic(false)));
        if (!lines.isEmpty()) stack.set(DataComponents.LORE, new ItemLore(lines));
        if (glint) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    private void refresh() {
        for (int i = 0; i < SIZE; i++) view.setItem(i, ItemStack.EMPTY);
        shown.clear();
        for (Places.Place place : Places.of(player.level().getServer(), Companies.of(player).id)) {
            if (!place.kind.equals(Places.DRONE_PORT) || Places.same(place, port.getLevel(), port.getBlockPos())) continue;
            if (!place.dimension.equals(port.getLevel().dimension().identifier().toString()) || shown.size() >= LIST) continue;
            boolean chosen = place.pos().equals(port.destination);
            int blocks = (int) Math.round(Math.sqrt(place.pos().distSqr(port.getBlockPos())));
            view.setItem(shown.size(), button(Items.ENDER_EYE, Component.literal(place.name), List.of(
                Component.translatable("siftec.place.where", place.x, place.y, place.z, blocks).withStyle(ChatFormatting.GRAY),
                Component.translatable(chosen ? "siftec.drone.chosen" : "siftec.drone.choose").withStyle(chosen ? ChatFormatting.GREEN : ChatFormatting.YELLOW)), chosen));
            shown.add(place);
        }
        int waiting = 0, arrived = 0;
        for (int i = PortContainer.OUT; i < PortContainer.FUEL; i++) waiting += port.items.getItem(i).getCount();
        for (int i = PortContainer.IN; i < PortContainer.SIZE; i++) arrived += port.items.getItem(i).getCount();
        view.setItem(STATUS, button(Items.CLOCK, Component.translatable(port.statusKey(), port.chargesNeeded()), List.of(
            Component.translatable("siftec.drone.waiting_count", waiting).withStyle(ChatFormatting.GRAY)), false));
        view.setItem(DRONE, port.hasDrone
            ? button(ModItems.CARDBOARD_DRONE.get(), Component.translatable("siftec.drone.take_drone"), List.of(), false)
            : button(Items.BARRIER, Component.translatable("siftec.drone.no_drone"), List.of(), false));
        view.setItem(COLLECT, button(Items.CHEST, Component.translatable("siftec.drone.collect", arrived), List.of(), arrived > 0));
        view.setItem(FUEL, button(Items.FIRE_CHARGE, Component.translatable("siftec.drone.fuel", port.fuel().getCount(), port.chargesNeeded()), List.of(
            Component.translatable("siftec.drone.fuel_how").withStyle(ChatFormatting.GRAY)), false));
    }

    private void press(int slot) {
        if (slot < shown.size()) {
            if (port.state != DronePortBlockEntity.IDLE) {
                player.sendOverlayMessage(Component.translatable("siftec.drone.away"));
            } else {
                port.destination = shown.get(slot).pos();
                port.setChanged();
            }
        } else if (slot == DRONE) {
            ItemStack drone = port.takeDrone();
            if (!drone.isEmpty()) player.getInventory().placeItemBackInInventory(drone, Prediction.SERVER_ONLY);
            else if (port.hasDrone) player.sendOverlayMessage(Component.translatable("siftec.drone.away"));
        } else if (slot == COLLECT) {
            for (int i = PortContainer.IN; i < PortContainer.SIZE; i++) {
                ItemStack stack = port.items.removeItemNoUpdate(i);
                if (!stack.isEmpty()) player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
            }
            port.setChanged();
        }
        refresh();
    }

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player who) {
        if (slot >= 0 && slot < SIZE) {
            if (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE) press(slot);
            return;
        }
        if (input == ContainerInput.PICKUP || input == ContainerInput.THROW) super.clicked(slot, button, input, who);
    }

    @Override
    public ItemStack quickMoveStack(Player who, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void broadcastChanges() {
        if (++ticks % 20 == 0) refresh();
        super.broadcastChanges();
    }

    /**
     * Whether the window stays open: the port still stands, the player is still there, alive and within reach of where
     * they opened it, and still in the company the port belongs to (as the Depot's window does).
     */
    @Override
    public boolean stillValid(Player who) {
        if (who != player || port.isRemoved() || !BuildingMenus.alive(player)) return false;
        if (player.level() != port.getLevel()) return false;
        if (!player.hasInfiniteMaterials() && !port.companyId.isEmpty() && !BuildingMenus.member(player, port.companyId)) return false;
        return BuildingMenus.withinReach(player, at, DepotMenu.REACH);
    }
}
