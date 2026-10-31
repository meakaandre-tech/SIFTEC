package com.meakaandre.siftec.client;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildGunPayloads;
import com.meakaandre.siftec.building.BuildHotbars;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.PowerRole;
import com.meakaandre.siftec.hub.Milestone;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The Build Gun's HUD, after Satisfactory's: the build hotbar in place of the vanilla hotbar while the gun is held,
 * the picked building near the crosshair (its name, whether it fits, its cost as have / need with what is missing first
 * in red, and its power), dismantle mode's target and selection with the hold-to-dismantle progress, and the key hints
 * bottom right.
 */
public final class BuildGunHud {
    /** Satisfactory's orange, for the picked slot and dismantle mode. */
    static final int ORANGE = 0xFFFA9549, WHITE = 0xFFFFFFFF, GREY = 0xFFB0B0B0, MISSING = 0xFFFF5555, FITS = 0xFF8FD3FF, PANEL = 0x90101418,
        SLOT = 0x80303840, SLOT_EMPTY = 0x50303840;
    static final int SLOT_SIZE = 20, SLOT_GAP = 2;
    /** Widest line of the info near the crosshair. */
    private static final int INFO_WIDTH = 190;
    /** SU in one MW (README: 1 MW = 204.8 SU). */
    static final double SU_PER_MW = 204.8;

    private BuildGunHud() {
    }

    static void register() {
        HudElementRegistry.replaceElement(VanillaHudElements.HOTBAR, vanilla -> (g, delta) -> {
            if (BuildGunClient.active()) hotbar(g);
            else vanilla.extractRenderState(g, delta);
        });
        HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, Siftec.id("build_gun"), BuildGunHud::info);
    }

    // ------------------------------------------------------------------------------------------------ the build hotbar

    /** The ten slots of the build hotbar shown, centred at the bottom where the vanilla hotbar is. */
    static void hotbar(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int n = BuildHotbars.SLOTS, total = n * SLOT_SIZE + (n - 1) * SLOT_GAP;
        int x0 = (g.guiWidth() - total) / 2, y0 = g.guiHeight() - SLOT_SIZE - 2;
        g.fill(x0 - 3, y0 - 3, x0 + total + 3, y0 + SLOT_SIZE + 2, PANEL);
        Component bar = Component.translatable("siftec.build_gun.hud.hotbar", BuildGunClient.page + 1);
        g.text(font, bar, x0 - 8 - font.width(bar), y0 + 6, GREY, true);
        for (int i = 0; i < n; i++) {
            int x = x0 + i * (SLOT_SIZE + SLOT_GAP);
            String id = BuildGunClient.inSlot(i);
            BuildingDef def = id.isEmpty() ? null : Buildings.def(id);
            g.fill(x, y0, x + SLOT_SIZE, y0 + SLOT_SIZE, def == null ? SLOT_EMPTY : SLOT);
            if (def != null) {
                g.item(BuildingVisuals.icon(def), x + 2, y0 + 2);
                if (!BuildGunClient.available(mc, id)) g.fill(x, y0, x + SLOT_SIZE, y0 + SLOT_SIZE, 0xA0000000);
            }
            boolean on = i == BuildGunClient.slot && !BuildGunClient.dismantle && id.equals(BuildGunClient.picked) && !id.isEmpty();
            if (on) {
                g.outline(x - 1, y0 - 1, SLOT_SIZE + 2, SLOT_SIZE + 2, ORANGE);
                g.outline(x, y0, SLOT_SIZE, SLOT_SIZE, ORANGE);
            }
            small(g, font, String.valueOf((i + 1) % 10), x + 1, y0 + 1, on ? ORANGE : WHITE);
        }
        BuildingDef picked = BuildGunClient.current();
        if (picked != null) {
            Component name = picked.name();
            g.text(font, name, (g.guiWidth() - font.width(name)) / 2, y0 - 14, WHITE, true);
        }
    }

    /** Text at 60% size. */
    private static void small(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(0.6f, 0.6f);
        g.text(font, text, 0, 0, color, true);
        g.pose().popMatrix();
    }

    // ------------------------------------------------------------------------------------------------ near the crosshair

    private static void info(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!BuildGunClient.active()) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
        List<Line> lines = new ArrayList<>();
        if (BuildGunClient.dismantle) {
            lines.add(new Line(Component.translatable("siftec.build_gun.hud.dismantle"), ORANGE, null));
            BuildGunPayloads.View v = BuildGunClient.verdict;
            if (v != null && !v.message().getString().isEmpty()) wrap(font, lines, v.message(), v.ok() ? WHITE : MISSING);
            if (!BuildGunClient.selection.isEmpty()) {
                lines.add(new Line(Component.translatable("siftec.build_gun.hud.selected", BuildGunClient.selection.size(), BuildGunPayloads.MAX_DISMANTLE), ORANGE, null));
            }
            if (!BuildGunClient.filter.isEmpty()) {
                BuildingDef f = Buildings.def(BuildGunClient.filter);
                lines.add(new Line(Component.translatable("siftec.build_gun.hud.filter", f == null ? Component.literal(BuildGunClient.filter) : f.name()), GREY, null));
            }
        } else {
            BuildingDef def = BuildGunClient.current();
            if (def != null) {
                lines.add(new Line(def.name(), WHITE, null));
                boolean ok = BuildGunClient.shown != null && BuildGunClient.fits(def, BuildGunClient.shown);
                Component status = BuildGunClient.status();
                if (!status.getString().isEmpty()) wrap(font, lines, status, ok ? FITS : MISSING);
                costLines(mc, def, lines);
                Component power = power(def);
                if (power != null) lines.add(new Line(power, GREY, null));
                if (BuildGunClient.locked) lines.add(new Line(Component.translatable("siftec.build_gun.hud.locked"), ORANGE, null));
            }
        }
        if (!lines.isEmpty()) {
            int x = cx + 14, y = cy - 6, w = 0;
            for (Line l : lines) w = Math.max(w, font.width(l.text) + (l.icon == null ? 0 : 14));
            g.fill(x - 3, y - 3, x + w + 3, y + lines.size() * 11 + 1, PANEL);
            for (Line l : lines) {
                int tx = x;
                if (l.icon != null) {
                    g.pose().pushMatrix();
                    g.pose().translate(x, y - 1);
                    g.pose().scale(0.625f, 0.625f);
                    g.item(l.icon, 0, 0);
                    g.pose().popMatrix();
                    tx += 14;
                }
                g.text(font, l.text, tx, y, l.color, true);
                y += 11;
            }
        }
        // left click held in dismantle mode: how far along the second is
        if (BuildGunClient.dismantle && BuildGunClient.held > 0) {
            int w = 40, filled = w * Math.min(BuildGunClient.held, BuildGunClient.HOLD_TICKS) / BuildGunClient.HOLD_TICKS;
            g.fill(cx - w / 2, cy + 9, cx + w / 2, cy + 12, PANEL);
            g.fill(cx - w / 2, cy + 9, cx - w / 2 + filled, cy + 12, ORANGE);
        }
        hints(g, font);
    }

    /** One line of the info: its text, colour, and the item drawn before it (a cost), if any. */
    private record Line(Component text, int color, ItemStack icon) {
    }

    private static void wrap(Font font, List<Line> lines, Component text, int color) {
        for (FormattedCharSequence part : font.split(text, INFO_WIDTH)) {
            MutableComponent c = Component.empty();
            part.accept((index, style, code) -> {
                c.append(Component.literal(Character.toString(code)).withStyle(style));
                return true;
            });
            lines.add(new Line(c, color, null));
        }
    }

    /** The building's cost as have / need, what is missing first, in red. */
    private static void costLines(Minecraft mc, BuildingDef def, List<Line> lines) {
        List<Milestone.Cost> costs = new ArrayList<>();
        for (Milestone.Cost c : def.cost()) if (c.present()) costs.add(c);
        costs.sort(Comparator.comparing((Milestone.Cost c) -> have(c) >= c.count()));
        for (Milestone.Cost c : costs) {
            int have = have(c);
            lines.add(new Line(Component.literal(have + " / " + c.count() + " ").append(c.label()), have >= c.count() ? WHITE : MISSING, c.icon()));
        }
        if (mc.player != null && mc.player.hasInfiniteMaterials() && !costs.isEmpty()) {
            lines.add(new Line(Component.translatable("siftec.build_gun.hud.creative"), GREY, null));
        }
    }

    /** How many of a cost the player carries, backpack included, as the server last said. */
    static int have(Milestone.Cost c) {
        return BuildGunClient.carried.getOrDefault(c.key(), 0);
    }

    /** What the building does to the grid: the MW it draws or makes, or null for none. */
    static Component power(BuildingDef def) {
        if (def.mw() > 0 && def.power() != PowerRole.NONE && def.power() != PowerRole.POLE && def.power() != PowerRole.STORAGE) {
            return Component.translatable("siftec.build_gun.hud.power_make", mw(def.mw()));
        }
        if (def.su() > 0) return Component.translatable("siftec.build_gun.hud.power_use", mw(def.su() / SU_PER_MW));
        return null;
    }

    static String mw(double mw) {
        return mw == Math.rint(mw) ? String.format(Locale.ROOT, "%,d", (long) mw) : String.format(Locale.ROOT, "%,.1f", mw);
    }

    // ------------------------------------------------------------------------------------------------ the key hints

    /** The keys that do something now, bottom right, as Satisfactory lists them. */
    private static void hints(GuiGraphicsExtractor g, Font font) {
        List<Component[]> hints = new ArrayList<>();
        if (BuildGunClient.dismantle) {
            hints.add(hint(name(BuildGunClient.PLACE), "hold_dismantle"));
            hints.add(hint(name(BuildGunClient.MULTI), "multi"));
            hints.add(hint(name(BuildGunClient.FILTER), "filter"));
            hints.add(hint(name(BuildGunClient.CANCEL), BuildGunClient.selection.isEmpty() ? "exit" : "clear"));
            hints.add(hint(name(BuildGunClient.DISMANTLE), "exit"));
        } else if (BuildGunClient.current() != null) {
            hints.add(hint(name(BuildGunClient.PLACE), "build"));
            hints.add(hint(name(BuildGunClient.CANCEL), "cancel"));
            hints.add(hint(Component.translatable("siftec.build_gun.key.scroll"), "rotate"));
            hints.add(hint(name(BuildGunClient.ALT).copy().append("+").append(name(BuildGunClient.MIRROR)), "mirror"));
            hints.add(hint(name(BuildGunClient.LOCK), BuildGunClient.locked ? "unlock" : "lock"));
            if (BuildGunClient.locked) hints.add(hint(nudgeKeys(), "nudge"));
            hints.add(hint(name(BuildGunClient.MENU), "menu"));
            hints.add(hint(name(BuildGunClient.DISMANTLE), "dismantle"));
        } else {
            hints.add(hint(name(BuildGunClient.MENU), "menu"));
            hints.add(hint(Component.literal("1-0"), "build"));
            hints.add(hint(name(BuildGunClient.ALT).copy().append("+").append(Component.translatable("siftec.build_gun.key.scroll")), "hotbar"));
            hints.add(hint(name(BuildGunClient.SAMPLE), "sample"));
            hints.add(hint(name(BuildGunClient.DISMANTLE), "dismantle"));
            hints.add(hint(name(BuildGunClient.CANCEL), "put_away"));
        }
        int right = g.guiWidth() - 6, y = g.guiHeight() - 8 - hints.size() * 12;
        for (Component[] h : hints) {
            int lw = font.width(h[1]), kw = font.width(h[0]);
            int lx = right - lw, kx = lx - 8 - kw;
            g.fill(kx - 3, y - 2, kx + kw + 3, y + 9, 0xC0202830);
            g.outline(kx - 3, y - 2, kw + 6, 11, 0xFF808890);
            g.text(font, h[0], kx, y, WHITE, false);
            g.text(font, h[1], lx, y, WHITE, true);
            y += 12;
        }
    }

    private static Component[] hint(Component key, String what) {
        return new Component[]{key, Component.translatable("siftec.build_gun.hint." + what)};
    }

    static Component name(KeyMapping key) {
        return key.getTranslatedKeyMessage();
    }

    /** The nudge keys: the arrows and Page Up/Down as short signs when they are on their default keys. */
    private static Component nudgeKeys() {
        KeyMapping[] keys = {BuildGunClient.NUDGE_FORWARD, BuildGunClient.NUDGE_BACK, BuildGunClient.NUDGE_LEFT, BuildGunClient.NUDGE_RIGHT,
            BuildGunClient.NUDGE_UP, BuildGunClient.NUDGE_DOWN};
        boolean defaults = true;
        for (KeyMapping k : keys) defaults &= k.isDefault();
        if (defaults) return Component.translatable("siftec.build_gun.key.nudge");
        MutableComponent out = Component.empty();
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) out.append(" ");
            out.append(name(keys[i]));
        }
        return out;
    }
}
