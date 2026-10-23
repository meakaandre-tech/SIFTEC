package com.meakaandre.siftec.sink;

import com.meakaandre.siftec.building.BuildingMenus;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.Logic;
import com.meakaandre.siftec.building.SpecialBuildings;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The AWESOME Sink's screen: the company's points, how far it is to the next FICSIT Coupon, and Print Coupons. Run by
 * the server, like the HUB window.
 */
public class SinkMenu extends ChestMenu {
    private static final int SIZE = 27, INFO = 11, PRINT = 13, STATUS = 15;
    private final SimpleContainer view;
    private final ServerPlayer player;
    /** The company whose points it shows (looked up by id on every refresh and click, so a fold is followed). */
    private final String companyId;
    /** The Sink's controller, which must stand and stay in reach. */
    private final BlockPos at;
    /** The viewer could print when the window opened (a member, or a Sink nobody owns); others only look. */
    private final boolean operator;
    private final BooleanSupplier powered;
    private int ticks;

    private SinkMenu(int id, Inventory inventory, SimpleContainer view, ServerPlayer player, String companyId, BlockPos at, boolean operator,
                     BooleanSupplier powered) {
        super(MenuType.GENERIC_9x3, id, inventory, view, 3);
        this.view = view;
        this.player = player;
        this.companyId = companyId;
        this.at = at.immutable();
        this.operator = operator;
        this.powered = powered;
        refresh();
    }

    /**
     * Opens the Sink at {@code at} showing {@code company}'s points. Anyone may look; only {@code operator}s (members
     * of the owning company, {@link SpecialBuildings#mayOperate}) may print.
     */
    public static void open(ServerPlayer player, Company company, BlockPos at, boolean operator, BooleanSupplier powered) {
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new SinkMenu(id, inventory, new SimpleContainer(SIZE), player, company.id, at,
            operator, powered), Component.translatable("siftec.sink.title")));
    }

    private @Nullable Company company() {
        return BuildingMenus.company(player, companyId);
    }

    /** True if the viewer may print right now: still allowed to operate the Sink that still stands. */
    private boolean mayPrint() {
        return player.level().getBlockEntity(at) instanceof ControllerBlockEntity c && SpecialBuildings.mayOperate(player, c);
    }

    static ItemStack button(Item icon, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(icon);
        stack.set(DataComponents.CUSTOM_NAME, Component.empty().append(name).withStyle(s -> s.withItalic(false)));
        List<Component> lines = new ArrayList<>();
        for (Component line : lore) lines.add(Component.empty().append(line).withStyle(s -> s.withItalic(false)));
        if (!lines.isEmpty()) stack.set(DataComponents.LORE, new ItemLore(lines));
        return stack;
    }

    /** "####------" for how far along to the next coupon. */
    static String bar(long have, long need) {
        int filled = need <= 0 ? 20 : (int) Math.min(20, have * 20 / need);
        return "|".repeat(filled) + ".".repeat(20 - filled);
    }

    private void refresh() {
        Company company = company();
        if (company == null) return;
        long next = Sink.couponCost(company.couponsPrinted + 1);
        view.setItem(INFO, button(Items.PAPER, Component.translatable("siftec.sink.points", company.points), List.of(
            Component.translatable("siftec.sink.status", company.points, company.couponsPrinted + 1, next).withStyle(ChatFormatting.GRAY),
            Component.literal(bar(company.points, next)).withStyle(company.points >= next ? ChatFormatting.GREEN : ChatFormatting.GOLD),
            Component.translatable("siftec.sink.printed", company.couponsPrinted).withStyle(ChatFormatting.GRAY),
            Component.translatable("siftec.sink.ever", company.pointsEver).withStyle(ChatFormatting.DARK_GRAY))));
        view.setItem(PRINT, button(ModItems.FICSIT_COUPON.get(), Component.translatable("siftec.sink.print").withStyle(ChatFormatting.YELLOW),
            List.of(Component.translatable(company.points >= next ? "siftec.sink.status" : "siftec.sink.print_none", company.points,
                company.couponsPrinted + 1, next).withStyle(company.points >= next ? ChatFormatting.GREEN : ChatFormatting.RED))));
        boolean on = powered.getAsBoolean();
        view.setItem(STATUS, button(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(on ? "lime_dye" : "red_dye")),
            on ? Component.translatable("siftec.sink.feed") : Component.translatable("siftec.sink.no_power").withStyle(ChatFormatting.RED), List.of()));
    }

    private void press(int slot) {
        if (slot != PRINT) return;
        Company company = company();
        if (company == null || !operator || !mayPrint()) {
            player.sendOverlayMessage(Component.translatable("siftec.place.not_yours"));
            return;
        }
        int made = Sink.print(company);
        if (made == 0) {
            player.sendOverlayMessage(Component.translatable("siftec.sink.print_none"));
            return;
        }
        player.getInventory().placeItemBackInInventory(new ItemStack(ModItems.FICSIT_COUPON.get(), made), Prediction.SERVER_ONLY);
        player.sendOverlayMessage(Component.translatable("siftec.sink.print_done", made));
        Companies.save(player.level().getServer());
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

    @Override
    public boolean stillValid(Player who) {
        // a member keeps it open while still a member; anyone else only looks, from within reach
        return who == player && BuildingMenus.near(player, at, BuildingMenus.logic(Logic.SINK))
            && company() != null && (!operator || mayPrint());
    }
}
