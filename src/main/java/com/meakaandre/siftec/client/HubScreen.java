package com.meakaandre.siftec.client;

import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.hub.HubBuilding;
import com.meakaandre.siftec.hub.HubMenu;
import com.meakaandre.siftec.hub.HubView;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The HUB screen, laid out like the old Tweaker HUB screen: tier tabs along the top, the tier's milestones down
 * the left, the chosen milestone's unlocks and costs (with progress bars) on the right, and the HUB status along
 * the bottom. Each tier also lists the HUB building, shown as the old screen's building card. Everything it shows
 * comes from the server ({@link HubView}); its buttons are menu button clicks the server checks again.
 */
public class HubScreen extends AbstractContainerScreen<HubMenu> {
    private static final int W = 320, H = 214;
    private static final int WORMHOLE_TAB = Milestones.TIERS;
    private static final String BUILDING = "#building";

    // the old screen's colours
    private static final int GOLD = 0xFFFFD060, GREY = 0xFFC0C0C0, DIM = 0xFF909090, WHITE = 0xFFFFFFFF, GREEN = 0xFF60FF60,
        ORANGE = 0xFFFF8060, RED = 0xFFFF6060, PURPLE = 0xFFC080FF, BAR_BACK = 0xFF303030, BAR = 0xFFE0A030;

    /** Icons for the building families, oldest first, as HubBuilding.FAMILIES. */
    private static final String[][] FAMILY_ICONS = {
        {"minecraft:stone_bricks", "minecraft:oak_planks", "minecraft:oak_log", "minecraft:polished_andesite"},
        {"create:andesite_casing", "create:andesite_alloy_block"},
        {"create:copper_casing", "minecraft:cut_copper", "minecraft:copper_block"},
        {"siftec:steel_casing", "cgs:steel_block"},
        {"create:brass_casing", "create:brass_block"}};
    private static final String[] HALF_ICONS = {"minecraft:oak_slab", "minecraft:oak_door", "minecraft:oak_trapdoor"};

    private HubView view;
    private int tab = -1;
    private String selected;
    private ItemStack hoverStack = ItemStack.EMPTY;
    private Component hoverExtra;
    private List<Component> hoverLines;

    public HubScreen(HubMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, W, H);
    }

    private Company company() {
        return view.asCompany();
    }

    // ------------------------------------------------------------------------------------------------ rules (as the server has them)

    private boolean tierOpen(int tier) {
        return Milestones.tierOpen(company(), tier);
    }

    private List<Milestone> tabMilestones() {
        return tab == WORMHOLE_TAB ? Milestones.phases() : Milestones.tier(tab);
    }

    private boolean showBuilding() {
        return tab != WORMHOLE_TAB && !view.gateway;
    }

    private long lock() {
        return view.lock;
    }

    /** Why the milestone cannot be paid here and now, or null. Mirrors HubMenu's checks. */
    private Component blocker(Milestone m) {
        if (view.done.contains(m.id())) return null;
        if (m.isPhase() != view.gateway) return Component.translatable(m.isPhase() ? "siftec.hub.at_gateway" : "siftec.hub.at_hub");
        Milestone b = Milestones.blocker(company(), m);
        if (b != null) return Component.translatable("siftec.hub.needs", b.name());
        if (!m.isPhase() && lock() > 0) return Component.translatable("siftec.hub.locked_for", HubMenu.clock(lock()));
        if (!m.isPhase() && m.tier() > view.built) return Component.translatable("siftec.hubui.build_first", m.tier());
        return null;
    }

    private int indexOf(Milestone m) {
        return m.index();
    }

    private void send(int action, Milestone m) {
        int tier = m == null ? 0 : m.isPhase() ? HubMenu.PHASES : m.tier();
        int id = HubMenu.button(action, tier, m == null ? 0 : indexOf(m));
        if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
    }

    /** The tab and milestone to show when the window opens: the active one, or the first unfinished one. */
    private void pickStart() {
        Milestone active = Milestones.get(view.active);
        if (view.gateway) {
            tab = WORMHOLE_TAB;
        } else if (active != null && !active.isPhase() && !active.isResearch()) {
            tab = active.tier();
        } else {
            tab = 0;
            for (int t = 0; t < Milestones.TIERS && tierOpen(t); t++) {
                boolean all = true;
                for (Milestone m : Milestones.tier(t)) all &= view.done.contains(m.id());
                if (!all) {
                    tab = t;
                    break;
                }
            }
        }
        selected = null;
    }

    private void fixSelection() {
        List<Milestone> list = tabMilestones();
        if (selected != null && (selected.equals(BUILDING) ? showBuilding() : list.stream().anyMatch(m -> m.id().equals(selected)))) return;
        selected = null;
        for (Milestone m : list) if (m.id().equals(view.active) && !view.done.contains(m.id())) selected = m.id();
        if (selected == null) for (Milestone m : list) if (selected == null && !view.done.contains(m.id())) selected = m.id();
        if (selected == null) selected = showBuilding() ? BUILDING : list.isEmpty() ? null : list.get(0).id();
    }

    // ------------------------------------------------------------------------------------------------ layout

    @Override
    protected void init() {
        super.init();
        if (view == null) return;
        int left = leftPos, top = topPos;
        for (int i = 0; i <= WORMHOLE_TAB; i++) {
            final int t = i;
            Component label = i == WORMHOLE_TAB ? Component.translatable("siftec.hubui.wormhole") : Component.literal(String.valueOf(i));
            int bw = i == WORMHOLE_TAB ? 52 : 20;
            int bx = left + 6 + (i < WORMHOLE_TAB ? i * 22 : 220);
            Button b = Button.builder(label, btn -> {
                tab = t;
                selected = null;
                rebuildWidgets();
            }).bounds(bx, top + 6, bw, 16).build();
            b.active = t != tab;
            addRenderableWidget(b);
        }
        fixSelection();
        int y = top + 28;
        if (showBuilding()) {
            boolean ok = view.exempt || view.built >= tab;
            Button b = Button.builder(Component.literal(ok ? "✔ " : "").append(Component.translatable("siftec.hubui.building")), btn -> {
                selected = BUILDING;
                rebuildWidgets();
            }).bounds(left + 6, y, 112, 18).build();
            b.active = !BUILDING.equals(selected);
            addRenderableWidget(b);
            y += 20;
        }
        for (Milestone m : tabMilestones()) {
            String mark = view.done.contains(m.id()) ? "✔ " : m.id().equals(view.active) ? "▶ " : "";
            Button b = Button.builder(Component.literal(mark).append(m.name()), btn -> {
                selected = m.id();
                rebuildWidgets();
            }).bounds(left + 6, y, 112, 18).build();
            b.active = !m.id().equals(selected);
            addRenderableWidget(b);
            y += 20;
        }

        Milestone sel = selected == null ? null : Milestones.get(selected);
        if (sel != null && !view.done.contains(sel.id())) {
            int bx = left + 124, by = top + H - 50;
            boolean blocked = blocker(sel) != null;
            if (!sel.id().equals(view.active)) {
                Button b = Button.builder(Component.translatable("siftec.hubui.set_active"), btn -> send(HubMenu.SELECT, sel))
                    .bounds(bx, by, 90, 18).build();
                b.active = !blocked;
                addRenderableWidget(b);
            } else {
                Button b = Button.builder(Component.translatable("siftec.hubui.pay"), btn -> send(HubMenu.PAY, sel))
                    .bounds(bx, by, 110, 18).build();
                b.active = !blocked;
                addRenderableWidget(b);
            }
        }
        if (!view.gateway) {
            addRenderableWidget(Button.builder(Component.translatable("siftec.hubui.rescan"), btn -> send(HubMenu.RESCAN, null))
                .bounds(left + W - 56, top + H - 24, 50, 18).build());
        } else if (view.done.contains("phase_5")) {
            addRenderableWidget(Button.builder(Component.translatable("siftec.sift.enter"), btn -> send(HubMenu.SIFT, null))
                .bounds(left + W - 96, top + H - 24, 90, 18).build());
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        HubView v = menu.view;
        if (v != null && v != view) {
            boolean first = view == null;
            view = v;
            if (first) pickStart();
            rebuildWidgets();
        }
    }

    // ------------------------------------------------------------------------------------------------ drawing

    private static String num(int n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static ItemStack stack(String id) {
        Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(Items.AIR);
        return new ItemStack(item);
    }

    private static boolean over(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        int left = leftPos, top = topPos;
        g.fill(left, top, left + W, top + H, 0xE0181410);
        g.outline(left, top, W, H, 0xFF6A3A2A);
        g.fill(left + 120, top + 26, left + 121, top + H - 28, 0xFF4A2A20);
        g.fill(left + 4, top + H - 26, left + W - 4, top + H - 25, 0xFF4A2A20);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // no title or inventory label: the old screen had neither
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int left = leftPos, top = topPos;
        if (view == null) {
            g.centeredText(font, Component.translatable("siftec.hubui.loading"), left + W / 2, top + H / 2 - 4, DIM);
            return;
        }
        hoverStack = ItemStack.EMPTY;
        hoverExtra = null;
        hoverLines = null;

        int x = left + 124, y = top + 28;
        if (tab == WORMHOLE_TAB) {
            int phases = 0;
            for (Milestone p : Milestones.phases()) if (view.done.contains(p.id())) phases++;
            Component line = view.gateway
                ? Component.translatable("siftec.hubui.phases", phases, Milestones.phases().size())
                : Component.translatable("siftec.hubui.phases_at_gateway", phases, Milestones.phases().size());
            g.text(font, line, left + 8, top + H - 34, view.gateway ? PURPLE : ORANGE, false);
        }
        boolean card = BUILDING.equals(selected) && showBuilding();
        if (tab != WORMHOLE_TAB && !tierOpen(tab) && !card) {
            Milestone gate = Milestones.blocker(company(), Milestones.tier(tab).get(0));
            g.text(font, Component.translatable("siftec.hubui.tier_locked", tab), x, y, RED, false);
            g.text(font, Component.translatable("siftec.hub.needs", gate == null ? Component.literal("?") : gate.name()), x, y + 12, 0xFFAAAAAA, false);
            y += 30;
        }
        if (card) {
            buildingCard(g, x, y, mouseX, mouseY);
        } else {
            Milestone sel = selected == null ? null : Milestones.get(selected);
            if (sel != null) milestone(g, sel, x, y, mouseX, mouseY);
        }
        statusBar(g, mouseX, mouseY);

        if (!hoverStack.isEmpty()) {
            List<Component> lines = new ArrayList<>(getTooltipFromItem(minecraft, hoverStack));
            if (hoverExtra != null) lines.add(hoverExtra.copy().withStyle(ChatFormatting.GRAY));
            g.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
        } else if (hoverLines != null) {
            g.setComponentTooltipForNextFrame(font, hoverLines, mouseX, mouseY);
        }
    }

    private void milestone(GuiGraphicsExtractor g, Milestone sel, int x, int y, int mouseX, int mouseY) {
        int top = topPos;
        boolean done = view.done.contains(sel.id());
        g.text(font, sel.name(), x, y, GOLD, false);
        y += 12;
        Component unlocks = Component.translatable("siftec.hub.unlocks", sel.unlockText());
        List<FormattedCharSequence> lines = font.split(unlocks, W - 132);
        int shown = Math.min(lines.size(), 3);
        for (int i = 0; i < shown; i++) {
            g.text(font, lines.get(i), x, y, GREY, false);
            y += 10;
        }
        if (lines.size() > shown && over(mouseX, mouseY, x, y - 10 * shown, W - 132, 10 * shown)) {
            hoverLines = List.of(unlocks);
        }
        y += 4;
        List<Milestone.Cost> costs = sel.cost();
        for (int i = 0; i < costs.size(); i++) {
            Milestone.Cost c = costs.get(i);
            if (!c.present()) continue;
            ItemStack icon = c.icon();
            g.item(icon, x, y);
            int need = view.need(c), have = done ? need : view.paid(sel, i);
            g.text(font, num(have) + " / " + num(need), x + 20, y + 1, have >= need ? GREEN : WHITE, false);
            int barX = x + 20, barY = y + 11, barW = 100;
            g.fill(barX, barY, barX + barW, barY + 3, BAR_BACK);
            g.fill(barX, barY, barX + (int) (barW * Math.min(1.0, have / (double) Math.max(1, need))), barY + 3, BAR);
            if (over(mouseX, mouseY, x, y, 16, 16)) {
                hoverStack = icon;
                hoverExtra = c.isTag() ? Component.translatable("siftec.hubui.any", c.label()) : null;
            }
            y += 20;
        }
        if (sel.seconds() > 0 && !done && y < top + H - 66) {
            g.text(font, Component.translatable("siftec.hubui.lock_after", HubMenu.clock(sel.seconds() * 20L)), x, y, DIM, false);
        }
        Component why = done ? Component.translatable("siftec.hubui.complete") : blocker(sel);
        if (why != null) {
            g.text(font, why, x, top + H - 64, done ? GREEN : ORANGE, false);
        } else {
            boolean active = sel.id().equals(view.active);
            String key = sel.isPhase() ? (active ? "siftec.hubui.active_gateway" : "siftec.hubui.hint_gateway") : (active ? "siftec.hubui.active" : "siftec.hubui.hint");
            g.text(font, Component.translatable(key), x, top + H - 64, DIM, false);
        }
    }

    /**
     * The tier's building goal, as the old screen's building card: total HUB blocks (any mix of the families this
     * tier allows), at least 50 of the newest family, and from Tier 1 walls and a roof.
     */
    private void buildingCard(GuiGraphicsExtractor g, int x, int y, int mouseX, int mouseY) {
        int t = tab, top = topPos;
        g.text(font, Component.translatable("siftec.hubui.building_for", t), x, y, GOLD, false);
        y += 12;
        int need = view.required(t), total = view.total(t);
        g.text(font, Component.translatable("siftec.hubui.blocks", num(total), num(need)), x, y, total >= need ? GREEN : WHITE, false);
        g.fill(x, y + 10, x + 170, y + 13, BAR_BACK);
        g.fill(x, y + 10, x + (int) (170 * Math.min(1.0, total / (double) Math.max(1, need))), y + 13, BAR);
        y += 17;
        if (t >= 1) {
            boolean ok = view.walls >= 2 && view.roof;
            g.text(font, Component.translatable("siftec.hubui.shelter", Math.min(view.walls, 2),
                Component.translatable(view.roof ? "siftec.boost.yes" : "siftec.boost.no")), x, y, ok ? GREEN : ORANGE, false);
            y += 12;
        }
        int newest = HubBuilding.newestFamily(t);
        for (int i = 0; i < HubBuilding.FAMILIES.size() && i < FAMILY_ICONS.length; i++) {
            HubBuilding.Family fam = HubBuilding.FAMILIES.get(i);
            if (fam.fromTier() > t) continue;
            int ix = x;
            Component famName = Component.translatable("siftec.building.family." + fam.key());
            for (String id : FAMILY_ICONS[i]) {
                ItemStack s = stack(id);
                if (s.isEmpty()) continue;
                g.item(s, ix, y);
                if (over(mouseX, mouseY, ix, y, 16, 16)) {
                    hoverStack = s;
                    hoverExtra = famName;
                }
                ix += 17;
            }
            boolean isNewest = i == newest;
            int count = view.counts[i];
            String n = isNewest ? num(count) + "/" + HubBuilding.NEW_MATERIAL : num(count);
            int colour = isNewest ? (count >= HubBuilding.NEW_MATERIAL ? GREEN : ORANGE) : GREY;
            g.text(font, n, ix + 2, y + 4, colour, false);
            if (isNewest && over(mouseX, mouseY, ix, y, 40, 16)) {
                hoverLines = List.of(Component.translatable("siftec.hubui.newest", HubBuilding.NEW_MATERIAL));
            }
            y += 17;
        }
        int ix = x;
        for (String id : HALF_ICONS) {
            ItemStack s = stack(id);
            g.item(s, ix, y);
            if (over(mouseX, mouseY, ix, y, 16, 16)) {
                hoverStack = s;
                hoverExtra = Component.translatable("siftec.hubui.half_tip");
            }
            ix += 17;
        }
        g.text(font, Component.translatable("siftec.hubui.half"), ix + 2, y + 4, GREY, false);

        Component msg;
        int colour = ORANGE;
        if (view.exempt) {
            msg = Component.translatable("siftec.hubui.exempt");
            colour = DIM;
        } else if (!view.marked) {
            msg = Component.translatable("siftec.hubui.not_marked");
        } else if (!view.inside) {
            msg = Component.translatable("siftec.building.hub_outside");
        } else if (view.tooLong || view.tooBig) {
            msg = Component.translatable("siftec.hubui.too_big");
        } else if (view.built >= t) {
            msg = Component.translatable("siftec.hubui.built", view.built);
            colour = GREEN;
        } else {
            msg = Component.translatable("siftec.hubui.build_more");
            colour = DIM;
        }
        List<FormattedCharSequence> lines = font.split(msg, W - 132);
        g.text(font, lines.get(0), x, top + H - 40, colour, false);
        if (lines.size() > 1 && over(mouseX, mouseY, x, top + H - 40, W - 132, 10)) hoverLines = List.of(msg);
    }

    private void statusBar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int left = leftPos, top = topPos, sy = top + H - 20;
        Component building = view.gateway ? Component.literal(view.company)
            : view.exempt ? Component.translatable("siftec.hubui.status_exempt")
            : view.built < 0 ? Component.translatable("siftec.hubui.status_none")
            : Component.translatable("siftec.hubui.status_built", Math.min(view.built, Milestones.TIERS - 1));
        Component state = lock() > 0 ? Component.translatable("siftec.hub.locked_for", HubMenu.clock(lock()))
            : Component.translatable("siftec.hub.ready");
        Component status = Component.translatable("siftec.hubui.status", building, view.members,
            String.format(Locale.ROOT, "%.1f", view.mult), state);
        boolean ok = (view.gateway || view.exempt || view.built >= 0) && lock() <= 0;
        g.text(font, status, left + 8, sy, ok ? 0xFF80FF80 : 0xFFFF8080, false);
        if (!view.gateway && over(mouseX, mouseY, left + 4, sy - 2, W - 64, 12)) hoverLines = report();
    }

    /** The building report for the next tier, as the HUB Planner prints it. */
    private List<Component> report() {
        List<Component> out = new ArrayList<>();
        if (view.exempt) {
            out.add(Component.translatable("siftec.hubui.exempt"));
            return out;
        }
        if (!view.marked) {
            out.add(Component.translatable("siftec.hubui.not_marked"));
            return out;
        }
        if (!view.inside) {
            out.add(Component.translatable("siftec.building.hub_outside"));
            return out;
        }
        if (view.tooLong || view.tooBig) {
            out.add(Component.translatable("siftec.hubui.too_big"));
            return out;
        }
        int tier = Math.min(Milestones.TIERS - 1, Math.max(0, view.built + 1));
        int newest = HubBuilding.newestFamily(tier);
        out.add(Component.translatable("siftec.building.blocks", tier, view.total(tier), view.required(tier)));
        out.add(Component.translatable("siftec.building.newest", Component.translatable("siftec.building.family." + HubBuilding.FAMILIES.get(newest).key()),
            view.counts[newest], HubBuilding.NEW_MATERIAL));
        if (tier >= 1) out.add(Component.translatable("siftec.building.shelter", view.walls, Component.translatable(view.roof ? "siftec.boost.yes" : "siftec.boost.no")));
        if (view.unloaded > 0) out.add(Component.translatableWithFallback("siftec.building.unloaded", "%s chunks of the marked area are not loaded and were not counted", view.unloaded));
        return out;
    }
}
