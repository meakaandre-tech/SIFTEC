package com.meakaandre.siftec.client;

import com.meakaandre.siftec.company.Company;
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
 * the left, the chosen milestone's unlocks and costs (with progress bars) on the right, and the HUB status (the
 * freighter's return, members and costs) along the bottom. Everything it shows comes from the server ({@link HubView}); its buttons are menu button clicks the server checks again.
 */
public class HubScreen extends AbstractContainerScreen<HubMenu> {
    private static final int W = 320, H = 214;
    private static final int WORMHOLE_TAB = Milestones.TIERS;

    // the old screen's colours
    private static final int GOLD = 0xFFFFD060, GREY = 0xFFC0C0C0, DIM = 0xFF909090, WHITE = 0xFFFFFFFF, GREEN = 0xFF60FF60,
        ORANGE = 0xFFFF8060, RED = 0xFFFF6060, PURPLE = 0xFFC080FF, BAR_BACK = 0xFF303030, BAR = 0xFFE0A030;


    private HubView view;
    private int tab = -1;
    private String selected;
    private ItemStack hoverStack = ItemStack.EMPTY;
    private Component hoverExtra;
    private List<Component> hoverLines;
    /** The shown tier is locked; its header already says why. */
    private boolean locked;

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
        if (selected != null && list.stream().anyMatch(m -> m.id().equals(selected))) return;
        selected = null;
        for (Milestone m : list) if (m.id().equals(view.active) && !view.done.contains(m.id())) selected = m.id();
        if (selected == null) for (Milestone m : list) if (selected == null && !view.done.contains(m.id())) selected = m.id();
        if (selected == null) selected = list.isEmpty() ? null : list.get(0).id();
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
        for (Milestone m : tabMilestones()) {
            String mark = view.done.contains(m.id()) ? "✔ " : m.id().equals(view.active) ? "▶ " : "";
            Button b = Button.builder(Component.literal(mark).append(m.name()), btn -> {
                selected = m.id();
                rebuildWidgets();
            }).bounds(left + 6, y, 112, 18).build();
            b.active = !m.id().equals(selected);
            // its Satisfactory-style text, in full
            b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(m.name().copy().withStyle(ChatFormatting.GOLD)
                .append("\n").append(m.desc().copy().withStyle(ChatFormatting.GRAY))));
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
        if (view.gateway && view.done.contains("phase_5")) {
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
            // under the phase list, as the old screen showed the Wormhole Generator
            Component status = phases >= Milestones.phases().size() ? Component.translatable("siftec.hubui.free_play")
                : Component.translatable("siftec.hubui.phases", phases, Milestones.phases().size());
            List<FormattedCharSequence> lines = font.split(status, 110);
            int ly = top + H - 30 - 10 * lines.size();
            for (FormattedCharSequence line : lines) {
                g.text(font, line, left + 8, ly, view.gateway ? PURPLE : ORANGE, false);
                ly += 10;
            }
        }
        locked = tab != WORMHOLE_TAB && !tierOpen(tab);
        if (locked) {
            Milestone gate = Milestones.blocker(company(), Milestones.tier(tab).get(0));
            g.text(font, Component.translatable("siftec.hubui.tier_locked", tab), x, y, RED, false);
            g.text(font, Component.translatable("siftec.hub.needs", gate == null ? Component.literal("?") : gate.name()), x, y + 10, 0xFFAAAAAA, false);
            y += 24;
        }
        Milestone sel = selected == null ? null : Milestones.get(selected);
        if (sel != null) milestone(g, sel, x, y, mouseX, mouseY);
        statusBar(g, mouseX, mouseY);

        if (!hoverStack.isEmpty()) {
            List<Component> lines = new ArrayList<>(getTooltipFromItem(minecraft, hoverStack));
            if (hoverExtra != null) lines.add(hoverExtra.copy().withStyle(ChatFormatting.GRAY));
            g.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
        } else if (hoverLines != null) {
            // wrapped to the panel's width, so a long line (a milestone's unlocks) never runs past it to the screen's edges
            g.setComponentTooltipForNextFrame(font, wrap(hoverLines, W - 16), mouseX, mouseY);
        }
    }

    /** Each line split into lines no wider than {@code width}, keeping its styles. */
    private List<Component> wrap(List<Component> lines, int width) {
        List<Component> out = new ArrayList<>();
        for (Component line : lines) {
            for (net.minecraft.network.chat.FormattedText part : font.getSplitter().splitLines(line, width, net.minecraft.network.chat.Style.EMPTY)) {
                net.minecraft.network.chat.MutableComponent c = Component.empty();
                part.visit((style, text) -> {
                    c.append(Component.literal(text).withStyle(style));
                    return java.util.Optional.empty();
                }, net.minecraft.network.chat.Style.EMPTY);
                out.add(c);
            }
        }
        return out;
    }

    private void milestone(GuiGraphicsExtractor g, Milestone sel, int x, int y, int mouseX, int mouseY) {
        int top = topPos, width = W - 132;
        boolean done = view.done.contains(sel.id());
        // the line above the buttons: why it cannot be paid, or what to do next (not repeated under a locked tier's header)
        Component why = done ? Component.translatable("siftec.hubui.complete") : locked ? null : blocker(sel);
        int whyColour = done ? GREEN : ORANGE;
        if (why == null && !done && !locked) {
            boolean active = sel.id().equals(view.active);
            why = Component.translatable(sel.isPhase() ? (active ? "siftec.hubui.active_gateway" : "siftec.hubui.hint_gateway")
                : (active ? "siftec.hubui.active" : "siftec.hubui.hint"));
            whyColour = DIM;
        }
        List<FormattedCharSequence> whyLines = why == null ? List.of() : font.split(why, width);
        if (whyLines.size() > 2) whyLines = whyLines.subList(0, 2);
        int bottom = top + H - 54 - 10 * whyLines.size();

        List<Milestone.Cost> costs = new ArrayList<>();
        List<Integer> lineOf = new ArrayList<>();
        for (int i = 0; i < sel.cost().size(); i++) {
            if (sel.cost().get(i).present()) {
                costs.add(sel.cost().get(i));
                lineOf.add(i);
            }
        }
        int row = costs.size() >= 3 ? 18 : 20;
        boolean lockLine = sel.seconds() > 0 && !done;

        g.text(font, sel.name(), x, y, GOLD, false);
        if (over(mouseX, mouseY, x, y, width, 10)) hoverLines = List.of(sel.name().copy().withStyle(ChatFormatting.GOLD), sel.desc());
        y += 12;
        Component unlocks = Component.translatable("siftec.hub.unlocks", sel.unlockText());
        List<FormattedCharSequence> lines = font.split(unlocks, width);
        int room = (bottom - y - 4 - costs.size() * row - (lockLine ? 10 : 0)) / 10;
        int shown = Math.max(1, Math.min(lines.size(), Math.min(3, room)));
        // the milestone's text (work package 13) under its name, in what room the unlocks and costs leave; all of it on hover
        List<FormattedCharSequence> about = font.split(sel.desc().copy().withStyle(ChatFormatting.ITALIC), width);
        int aboutShown = Math.max(0, Math.min(about.size(), Math.min(4, room - shown - 1)));
        for (int i = 0; i < aboutShown; i++) {
            g.text(font, about.get(i), x, y, DIM, false);
            y += 10;
        }
        if (aboutShown > 0) {
            if (over(mouseX, mouseY, x, y - 10 * aboutShown, width, 10 * aboutShown)) hoverLines = List.of(sel.desc());
            y += 3;
        }
        for (int i = 0; i < shown; i++) {
            g.text(font, lines.get(i), x, y, GREY, false);
            y += 10;
        }
        if (lines.size() > shown && over(mouseX, mouseY, x, y - 10 * shown, width, 10 * shown)) hoverLines = List.of(unlocks);
        y += 4;
        for (int k = 0; k < costs.size(); k++) {
            Milestone.Cost c = costs.get(k);
            ItemStack icon = c.icon();
            g.item(icon, x, y);
            int need = view.need(c), have = done ? need : view.paid(sel, lineOf.get(k));
            g.text(font, num(have) + " / " + num(need), x + 20, y + 1, have >= need ? GREEN : WHITE, false);
            int barX = x + 20, barY = y + 11, barW = 100;
            g.fill(barX, barY, barX + barW, barY + 3, BAR_BACK);
            g.fill(barX, barY, barX + (int) (barW * Math.min(1.0, have / (double) Math.max(1, need))), barY + 3, BAR);
            if (over(mouseX, mouseY, x, y, 16, 16)) {
                hoverStack = icon;
                hoverExtra = c.isTag() ? Component.translatable("siftec.hubui.any", c.label()) : null;
            }
            y += row;
        }
        if (lockLine && y + 9 <= bottom) {
            g.text(font, Component.translatable("siftec.hubui.lock_after", HubMenu.clock(sel.seconds() * 20L)), x, y, DIM, false);
        }
        int wy = bottom + 1;
        for (FormattedCharSequence line : whyLines) {
            g.text(font, line, x, wy, whyColour, false);
            wy += 10;
        }
    }

    private void statusBar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int left = leftPos, top = topPos, sy = top + H - 20;
        Component first = Component.literal(view.company);
        String mult = view.mult == Math.round(view.mult) ? String.valueOf(Math.round(view.mult)) : String.format(Locale.ROOT, "%.1f", view.mult);
        Component status = Component.translatable("siftec.hubui.status", first, view.members, mult);
        if (lock() > 0) status = Component.translatable("siftec.hubui.status_locked", status, HubMenu.clock(lock()));
        g.text(font, status, left + 8, sy, lock() <= 0 ? 0xFF80FF80 : 0xFFFF8080, false);
    }

}
