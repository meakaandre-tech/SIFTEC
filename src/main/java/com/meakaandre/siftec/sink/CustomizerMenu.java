package com.meakaandre.siftec.sink;

import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.Paint;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Customizer's window for one building. The top two rows are the company's colour swatches (click: paint the
 * building with it; right-click: edit it); below them the sixteen colours (click: the edited swatch's primary colour,
 * right-click: its secondary) and the industrial grey; then the finishes, and the casing skins. Locked ones say to buy
 * them in the AWESOME Shop. Run by the server, like the HUB window.
 */
public class CustomizerMenu extends ChestMenu {
    private static final int SIZE = 54, PALETTE = 18, INDUSTRIAL = 34, RESET = 35, FINISHES = 36, SKINS = 45;
    private final SimpleContainer view;
    private final ServerPlayer player;
    private final Company company;
    private final BlockPos controller;
    private int editing;

    private CustomizerMenu(int id, Inventory inventory, SimpleContainer view, ServerPlayer player, Company company, BlockPos controller) {
        super(MenuType.GENERIC_9x6, id, inventory, view, 6);
        this.view = view;
        this.player = player;
        this.company = company;
        this.controller = controller;
        refresh();
    }

    public static void open(ServerPlayer player, Company company, BlockPos controller) {
        Component name = player.level().getBlockEntity(controller) instanceof ControllerBlockEntity c && c.def() != null ? c.def().name() : Component.empty();
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new CustomizerMenu(id, inventory, new SimpleContainer(SIZE), player, company, controller),
            Component.translatable("siftec.customizer.title", name)));
    }

    private @Nullable ControllerBlockEntity building() {
        return player.level().getBlockEntity(controller) instanceof ControllerBlockEntity c ? c : null;
    }

    private boolean storage() {
        ControllerBlockEntity c = building();
        BuildingDef def = c == null ? null : c.def();
        return def != null && def.logic().equals("storage");
    }

    /** "foundation", "wall" or "roof" for a Foundations-tab building that takes materials; null for any other. */
    private @Nullable String materialKind() {
        ControllerBlockEntity c = building();
        BuildingDef def = c == null ? null : c.def();
        if (def == null || !def.logic().equals("foundation")) return null;
        String id = def.id();
        return id.startsWith("foundation_") ? "foundation" : id.startsWith("wall_") ? "wall" : id.startsWith("roof_") ? "roof" : null;
    }

    private List<String> materialsFor(String kind) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : Shop.materials().entrySet()) if (e.getValue().equals(kind)) out.add(e.getKey());
        return out;
    }

    /** A foundation's, wall's or roof's window: its materials on the top row, a foundation's floor patterns on the third. */
    private void refreshMaterials(String kind, Paint paint) {
        ItemStack own = SinkMenu.button(Items.GRAY_CONCRETE, Component.translatable("siftec.customizer.material", Component.translatable("siftec.customizer.skin_none")), List.of());
        if (paint.material().isEmpty()) own.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        view.setItem(0, own);
        int k = 1;
        for (String m : materialsFor(kind)) {
            boolean open = Shop.owns(player, company, "material:" + m);
            Shop.Entry e = Shop.entry("material_" + m);
            ItemStack stack = SinkMenu.button(open && e != null ? ShopMenu.icon(e) : Items.BARRIER,
                Component.translatable("siftec.customizer.material", Component.translatable("siftec.material." + m)),
                open ? List.of() : List.of(Component.translatable("siftec.customizer.locked").withStyle(ChatFormatting.RED)));
            if (paint.material().equals(m)) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            if (k < 9) view.setItem(k++, stack);
        }
        if (!kind.equals("foundation")) return;
        ItemStack none = SinkMenu.button(Items.LIGHT_GRAY_CONCRETE, Component.translatable("siftec.customizer.pattern", Component.translatable("siftec.customizer.pattern_none")), List.of());
        if (paint.pattern().isEmpty()) none.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        view.setItem(PALETTE, none);
        k = 1;
        for (String pattern : Shop.patterns()) {
            boolean open = Shop.owns(player, company, "pattern:" + pattern);
            ItemStack stack = SinkMenu.button(open ? (pattern.equals("factory_zone") ? Items.BLACK_CONCRETE : Items.YELLOW_CONCRETE) : Items.BARRIER,
                Component.translatable("siftec.customizer.pattern", Component.translatable("siftec.pattern." + pattern)),
                open ? List.of() : List.of(Component.translatable("siftec.customizer.locked").withStyle(ChatFormatting.RED)));
            if (paint.pattern().equals(pattern)) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            if (k < 9) view.setItem(PALETTE + k++, stack);
        }
    }

    private void pressMaterials(String kind, int slot, Paint paint) {
        if (slot == 0) {
            apply(paint.withMaterial(""));
        } else if (slot < 9) {
            List<String> ms = materialsFor(kind);
            if (slot - 1 >= ms.size()) return;
            String m = ms.get(slot - 1);
            if (!Shop.owns(player, company, "material:" + m)) {
                player.sendOverlayMessage(Component.translatable("siftec.customizer.locked"));
                return;
            }
            apply(paint.withMaterial(m));
        } else if (kind.equals("foundation") && slot == PALETTE) {
            apply(paint.withPattern(""));
        } else if (kind.equals("foundation") && slot > PALETTE && slot < PALETTE + 9) {
            List<String> ps = Shop.patterns();
            if (slot - PALETTE - 1 >= ps.size()) return;
            String pattern = ps.get(slot - PALETTE - 1);
            if (!Shop.owns(player, company, "pattern:" + pattern)) {
                player.sendOverlayMessage(Component.translatable("siftec.customizer.locked"));
                return;
            }
            apply(paint.withPattern(pattern));
        }
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(Items.BARRIER);
    }

    private static Item concrete(String dye) {
        return dye.equals("industrial") ? item("create:industrial_iron_block") : item("minecraft:" + dye + "_concrete");
    }

    private static Component colour(String dye) {
        return dye.equals("industrial") ? Component.translatable("siftec.customizer.industrial") : Component.translatable("color.minecraft." + dye);
    }

    /** The skins this building can take: every building's, and the Storage Container's own on storage. */
    private List<Map.Entry<String, String>> skins() {
        List<Map.Entry<String, String>> out = new ArrayList<>(Shop.skins().entrySet());
        if (storage()) out.addAll(Shop.storageSkins().entrySet());
        return out;
    }

    private void refresh() {
        for (int i = 0; i < SIZE; i++) view.setItem(i, ItemStack.EMPTY);
        ControllerBlockEntity c = building();
        Paint paint = c == null ? Paint.NONE : Paint.parse(c.paint);
        String kind = materialKind();
        if (kind != null) {
            refreshMaterials(kind, paint);
            return;
        }
        for (int i = 0; i < company.swatches.size() && i < 18; i++) {
            String[] sw = company.swatches.get(i).split("/", -1);
            String secondary = sw.length > 1 ? sw[1] : "industrial";
            ItemStack stack = SinkMenu.button(concrete(sw[0]), Component.translatable("siftec.customizer.swatch", i + 1, colour(sw[0]), colour(secondary)),
                List.of(Component.translatable("siftec.customizer.swatch_pick").withStyle(ChatFormatting.GRAY)));
            if (i == editing) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            view.setItem(i, stack);
        }
        List<String> dyes = Shop.dyes();
        for (int i = 0; i < dyes.size() && i < 16; i++) {
            view.setItem(PALETTE + i, SinkMenu.button(concrete(dyes.get(i)), colour(dyes.get(i)),
                List.of(Component.translatable("siftec.customizer.primary", editing + 1).withStyle(ChatFormatting.GRAY))));
        }
        view.setItem(INDUSTRIAL, SinkMenu.button(concrete("industrial"), colour("industrial"),
            List.of(Component.translatable("siftec.customizer.primary", editing + 1).withStyle(ChatFormatting.GRAY))));
        view.setItem(RESET, SinkMenu.button(Items.BRUSH, Component.translatable("siftec.customizer.reset"), List.of()));
        int f = 0;
        for (Map.Entry<String, String> e : Shop.finishes().entrySet()) {
            boolean open = Shop.hasFinish(company, e.getKey()) || player.hasInfiniteMaterials();
            Item icon = open ? item(e.getValue().replace("{colour}", paint.primary())) : Items.BARRIER;
            ItemStack stack = SinkMenu.button(icon, Component.translatable("siftec.customizer.finish", Component.translatable("siftec.finish." + e.getKey())),
                open ? List.of() : List.of(Component.translatable("siftec.customizer.locked").withStyle(ChatFormatting.RED)));
            if (paint.finish().equals(e.getKey())) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            if (f < 9) view.setItem(FINISHES + f++, stack);
        }
        ItemStack none = SinkMenu.button(Items.IRON_BLOCK, Component.translatable("siftec.customizer.skin", Component.translatable("siftec.customizer.skin_none")), List.of());
        if (paint.skin().isEmpty()) none.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        view.setItem(SKINS, none);
        int k = 1;
        for (Map.Entry<String, String> e : skins()) {
            if (k >= 9) break;
            boolean open = Shop.hasSkin(company, e.getKey()) || player.hasInfiniteMaterials();
            ItemStack stack = SinkMenu.button(open ? item(e.getValue()) : Items.BARRIER,
                Component.translatable("siftec.customizer.skin", Component.translatable("siftec.skin." + e.getKey())),
                open ? List.of() : List.of(Component.translatable("siftec.customizer.locked").withStyle(ChatFormatting.RED)));
            if (paint.skin().equals(e.getKey())) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            view.setItem(SKINS + k++, stack);
        }
    }

    private void apply(Paint paint) {
        ControllerBlockEntity c = building();
        if (c == null || !(player.level() instanceof ServerLevel level)) return;
        Buildings.repaint(level, c, paint);
        if (c.def() != null) player.sendOverlayMessage(Component.translatable("siftec.customizer.applied", c.def().name()));
    }

    private void press(int slot, boolean right) {
        ControllerBlockEntity c = building();
        if (c == null) return;
        Paint paint = Paint.parse(c.paint);
        String kind = materialKind();
        if (kind != null) {
            pressMaterials(kind, slot, paint);
            refresh();
            return;
        }
        if (slot < 18 && slot < company.swatches.size()) {
            if (right) editing = slot;
            else apply(paint.withSwatch(company.swatches.get(slot)));
        } else if (slot >= PALETTE && slot < PALETTE + 16 || slot == INDUSTRIAL) {
            if (editing >= company.swatches.size()) return;
            String dye = slot == INDUSTRIAL ? "industrial" : Shop.dyes().get(slot - PALETTE);
            String[] sw = company.swatches.get(editing).split("/", -1);
            String primary = sw[0], secondary = sw.length > 1 ? sw[1] : "industrial";
            // the industrial grey is only a secondary colour: the primary panels are paint
            if (right || slot == INDUSTRIAL) secondary = dye;
            else primary = dye;
            company.swatches.set(editing, primary + "/" + secondary);
            Companies.save(player.level().getServer());
            player.sendOverlayMessage(Component.translatable("siftec.customizer.editing", editing + 1));
        } else if (slot == RESET) {
            apply(Paint.NONE);
        } else if (slot >= FINISHES && slot < FINISHES + 9) {
            List<String> ids = new ArrayList<>(Shop.finishes().keySet());
            int i = slot - FINISHES;
            if (i >= ids.size()) return;
            if (!Shop.hasFinish(company, ids.get(i)) && !player.hasInfiniteMaterials()) {
                player.sendOverlayMessage(Component.translatable("siftec.customizer.locked"));
                return;
            }
            apply(paint.withFinish(ids.get(i)));
        } else if (slot == SKINS) {
            apply(paint.withSkin(""));
        } else if (slot > SKINS && slot < SIZE) {
            List<Map.Entry<String, String>> skins = skins();
            int i = slot - SKINS - 1;
            if (i >= skins.size()) return;
            if (!Shop.hasSkin(company, skins.get(i).getKey()) && !player.hasInfiniteMaterials()) {
                player.sendOverlayMessage(Component.translatable("siftec.customizer.locked"));
                return;
            }
            apply(paint.withSkin(skins.get(i).getKey()));
        }
        refresh();
    }

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player who) {
        if (slot >= 0 && slot < SIZE) {
            if (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE) press(slot, input == ContainerInput.PICKUP && button == 1);
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
        return building() != null;
    }
}
