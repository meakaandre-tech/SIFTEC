package com.meakaandre.siftec.client;

import com.meakaandre.siftec.building.BuildMenu;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.client.ponder.Guide;
import com.meakaandre.siftec.hub.Milestone;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Build Gun's build menu (Q), laid out like Satisfactory 1.0's: the categories down the left, the chosen category's
 * subcategories as headings over a grid of building icons six to a row, a search box along the top (searching every
 * category), and on the right the building under the mouse: its name, a turning 3D view of it, its description, its cost
 * as have / need with what is missing in red, its power and its footprint. Only buildings the company has unlocked are
 * there. Left click picks one for the hologram, right click opens its Ponder scene, and 1 to 0 over one binds it to that
 * slot of the build hotbar shown.
 */
public class BuildMenuScreen extends Screen {
    /** One building icon in the grid, in GUI coordinates (its top left corner). */
    public record Cell(BuildingDef def, int x, int y) {
    }

    static final int CELL = 30, COLUMNS = 6, MARGIN = 8, HEADER = 28, LEFT_WIDTH = 92, SECTION_ROW = 16, HEADING = 13;
    private static final int ORANGE = BuildGunHud.ORANGE, WHITE = 0xFFFFFFFF, GREY = 0xFFB0B0B0, DIM = 0xFF808890,
        BACK = 0xE0161A20, PANEL = 0xC0242A32, CELL_BACK = 0xFF2E3640, CELL_HOVER = 0xFF3C4654, MISSING = BuildGunHud.MISSING;
    /** Lines of a building's description the panel shows at most. */
    private static final int MAX_ABOUT_LINES = 6;
    /** How long the "bound to slot" line stays (ms). */
    private static final long FLASH_MILLIS = 2500;

    /** The category shown (kept between openings, as Satisfactory does). */
    private static String section = "";
    private EditBox search;
    private int gridX, gridY, gridW, gridH, detailX, detailW;
    private double scroll;
    private final List<Cell> cells = new ArrayList<>();
    private @Nullable BuildingDef hovered, detail;
    private Component flash = Component.empty();
    private long flashUntil;

    public BuildMenuScreen() {
        super(Component.translatable("siftec.build_menu.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------------------------------------ what it shows

    /** The ids the player may build: what the server said is unlocked, or everything for a creative player it has not told yet. */
    private Set<String> unlocked() {
        if (BuildGunClient.unlocked.isEmpty() && minecraft.player != null && minecraft.player.hasInfiniteMaterials()) {
            Set<String> all = new HashSet<>();
            for (BuildingDef def : Buildings.all()) all.add(def.id());
            return all;
        }
        return BuildGunClient.unlocked;
    }

    private String query() {
        return search == null ? "" : search.getValue();
    }

    /** The categories with something unlocked in them, in the menu's order. */
    List<String> sections() {
        List<String> out = new ArrayList<>();
        List<BuildingDef> all = BuildMenu.visible(unlocked(), "");
        for (String s : BuildMenu.sections()) {
            for (BuildingDef def : all) {
                if (BuildMenu.place(def).section().equals(s)) {
                    out.add(s);
                    break;
                }
            }
        }
        return out;
    }

    /** The buildings in the grid: the category's, or with a search every category's that match. */
    List<BuildingDef> shownBuildings() {
        List<BuildingDef> list = BuildMenu.visible(unlocked(), query());
        if (!query().isBlank()) return list;
        List<BuildingDef> out = new ArrayList<>();
        for (BuildingDef def : list) if (BuildMenu.place(def).section().equals(section)) out.add(def);
        return out;
    }

    /** The grid's cells as last laid out (for the selftests: where to point at a building). */
    public List<Cell> cells() {
        return List.copyOf(cells);
    }

    /** Types a search, as the player would (for the selftests). */
    public void searchFor(String text) {
        search.setValue(text);
        scroll = 0;
        layout();
    }

    /** The search box's middle, in GUI coordinates (for the selftests). */
    public int[] searchCentre() {
        return new int[]{search.getX() + search.getWidth() / 2, search.getY() + search.getHeight() / 2};
    }

    // ------------------------------------------------------------------------------------------------ layout

    @Override
    protected void init() {
        List<String> sections = sections();
        if (!sections.contains(section)) section = sections.isEmpty() ? "" : sections.getFirst();
        gridX = MARGIN + LEFT_WIDTH + MARGIN;
        gridY = HEADER + MARGIN;
        gridW = COLUMNS * CELL;
        gridH = height - gridY - 22;
        detailX = gridX + gridW + MARGIN * 2;
        detailW = width - detailX - MARGIN;
        String old = query();
        search = new EditBox(font, gridX, 8, gridW, 14, Component.translatable("siftec.build_menu.search"));
        search.setHint(Component.translatable("siftec.build_menu.search").withStyle(EditBox.SEARCH_HINT_STYLE));
        search.setValue(old);
        search.setResponder(text -> scroll = 0);
        addRenderableWidget(search);
        layout();
    }

    /** Places the grid's cells: a heading over each subcategory, then its buildings six to a row. */
    private void layout() {
        cells.clear();
        Map<String, List<BuildingDef>> groups = new LinkedHashMap<>();
        boolean searching = !query().isBlank();
        for (BuildingDef def : shownBuildings()) {
            BuildMenu.Place p = BuildMenu.place(def);
            groups.computeIfAbsent((searching ? p.section() + "/" : "") + p.sub(), k -> new ArrayList<>()).add(def);
        }
        int y = gridY - (int) scroll;
        for (List<BuildingDef> group : groups.values()) {
            y += HEADING;
            for (int i = 0; i < group.size(); i++) {
                cells.add(new Cell(group.get(i), gridX + (i % COLUMNS) * CELL, y + (i / COLUMNS) * CELL));
            }
            y += ((group.size() + COLUMNS - 1) / COLUMNS) * CELL + 4;
        }
    }

    /** How tall the grid's content is. */
    private int contentHeight() {
        int bottom = gridY;
        for (Cell c : cells) bottom = Math.max(bottom, c.y + CELL + (int) scroll);
        return bottom - gridY;
    }

    // ------------------------------------------------------------------------------------------------ input

    private @Nullable Cell cellAt(double mx, double my) {
        if (mx < gridX || mx >= gridX + gridW || my < gridY || my >= gridY + gridH) return null;
        for (Cell c : cells) if (mx >= c.x && mx < c.x + CELL - 2 && my >= c.y && my < c.y + CELL - 2) return c;
        return null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (search.isMouseOver(event.x(), event.y())) return super.mouseClicked(event, doubleClick);
        setFocused(null);
        // a category
        List<String> sections = sections();
        for (int i = 0; i < sections.size(); i++) {
            int y = gridY + i * SECTION_ROW;
            if (event.x() >= MARGIN && event.x() < MARGIN + LEFT_WIDTH && event.y() >= y && event.y() < y + SECTION_ROW - 2) {
                section = sections.get(i);
                search.setValue("");
                scroll = 0;
                layout();
                return true;
            }
        }
        Cell cell = cellAt(event.x(), event.y());
        if (cell == null) return super.mouseClicked(event, doubleClick);
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            // the hologram appears as soon as the menu closes
            BuildGunClient.pick(cell.def.id());
            BuildGunClient.slot = -1;
            onClose();
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            // its Ponder scene (Satisfactory's Codex)
            Guide.open(cell.def);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (mx >= gridX && mx < gridX + gridW && my >= gridY && my < gridY + gridH) {
            scroll = Math.max(0, Math.min(Math.max(0, contentHeight() - gridH), scroll - sy * CELL));
            layout();
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (search.isFocused()) {
            // Enter (or Escape) leaves the search box, so 1 to 0 bind again
            if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_ESCAPE) {
                setFocused(null);
                search.setFocused(false);
                return true;
            }
            return super.keyPressed(event);
        }
        for (int i = 0; i < BuildGunClient.SLOTS.length; i++) {
            if (!BuildGunClient.SLOTS[i].matches(event)) continue;
            BuildingDef def = hovered;
            if (def != null) {
                BuildGunClient.bind(i, def.id());
                flash = Component.translatable("siftec.build_menu.bound", def.name(), i + 1, BuildGunClient.page + 1);
                flashUntil = System.currentTimeMillis() + FLASH_MILLIS;
            }
            return true;
        }
        if (BuildGunClient.MENU.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    // ------------------------------------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        layout();
        g.fill(0, 0, width, height, BACK);
        g.text(font, title, MARGIN, 11, ORANGE, true);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        sectionsColumn(g, mouseX, mouseY);
        Cell over = cellAt(mouseX, mouseY);
        hovered = over == null ? null : over.def;
        if (hovered != null) detail = hovered;
        grid(g, over);
        List<BuildingDef> shown = shownBuildings();
        if (detail == null || !shown.contains(detail)) detail = hovered != null ? hovered : shown.isEmpty() ? null : shown.getFirst();
        if (detail != null) detailPanel(g, detail);
        Component hint = System.currentTimeMillis() < flashUntil ? flash : Component.translatable("siftec.build_menu.hint", BuildGunClient.page + 1);
        g.text(font, hint, MARGIN, height - 14, System.currentTimeMillis() < flashUntil ? ORANGE : DIM, false);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // the menu draws its own backdrop over the world
    }

    private void sectionsColumn(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        List<String> sections = sections();
        boolean searching = !query().isBlank();
        for (int i = 0; i < sections.size(); i++) {
            String s = sections.get(i);
            int y = gridY + i * SECTION_ROW;
            boolean on = s.equals(section) && !searching;
            boolean over = mouseX >= MARGIN && mouseX < MARGIN + LEFT_WIDTH && mouseY >= y && mouseY < y + SECTION_ROW - 2;
            g.fill(MARGIN, y, MARGIN + LEFT_WIDTH, y + SECTION_ROW - 2, on ? ORANGE : over ? CELL_HOVER : PANEL);
            g.text(font, BuildMenu.sectionName(s), MARGIN + 5, y + 3, on ? 0xFF1A1A1A : WHITE, false);
        }
    }

    private void grid(GuiGraphicsExtractor g, @Nullable Cell over) {
        g.enableScissor(gridX, gridY, gridX + gridW, gridY + gridH);
        if (cells.isEmpty()) {
            g.text(font, Component.translatable(query().isBlank() ? "siftec.build_menu.empty" : "siftec.build_menu.no_match"), gridX, gridY + 4, DIM, false);
        }
        String lastHeading = null;
        boolean searching = !query().isBlank();
        for (Cell c : cells) {
            BuildMenu.Place p = BuildMenu.place(c.def);
            String heading = (searching ? p.section() + "/" : "") + p.sub();
            if (!heading.equals(lastHeading) && c.x == gridX) {
                MutableComponent text = searching ? BuildMenu.sectionName(p.section()).copy().append(" / ").append(BuildMenu.subName(p.sub()))
                    : BuildMenu.subName(p.sub()).copy();
                g.text(font, text, gridX, c.y - HEADING + 2, GREY, false);
                g.fill(gridX, c.y - 2, gridX + gridW - 2, c.y - 1, 0x60FFFFFF);
                lastHeading = heading;
            }
            boolean on = over != null && over.def.equals(c.def);
            g.fill(c.x, c.y, c.x + CELL - 2, c.y + CELL - 2, on ? CELL_HOVER : CELL_BACK);
            if (on) g.outline(c.x, c.y, CELL - 2, CELL - 2, ORANGE);
            g.pose().pushMatrix();
            g.pose().translate(c.x + 2, c.y + 2);
            g.pose().scale(1.5f, 1.5f);
            g.item(BuildingVisuals.icon(c.def), 0, 0);
            g.pose().popMatrix();
            // its hotbar slot, if it is on the bar shown
            for (int i = 0; i < BuildGunClient.SLOTS.length; i++) {
                if (BuildGunClient.inSlot(i).equals(c.def.id())) {
                    g.text(font, String.valueOf((i + 1) % 10), c.x + CELL - 8, c.y + CELL - 11, ORANGE, true);
                    break;
                }
            }
        }
        g.disableScissor();
    }

    private void detailPanel(GuiGraphicsExtractor g, BuildingDef def) {
        int x = detailX, w = detailW, y = gridY;
        g.fill(x - 4, y - 4, x + w, height - 22, PANEL);
        g.text(font, def.name(), x, y, ORANGE, true);
        y += 12;
        int previewH = Math.min(110, (height - gridY) / 3);
        g.fill(x, y, x + w - 4, y + previewH, 0x60000000);
        BuildingVisuals.preview(g, def, x + 2, y + 2, w - 8, previewH - 4, System.currentTimeMillis());
        y += previewH + 6;
        List<FormattedCharSequence> about = font.split(description(def), w - 6);
        for (int i = 0; i < Math.min(about.size(), MAX_ABOUT_LINES); i++) {
            g.text(font, about.get(i), x, y, GREY, false);
            y += 10;
        }
        y += 4;
        // its cost as have / need, what is missing first, in red
        g.text(font, Component.translatable("siftec.build_menu.cost"), x, y, WHITE, false);
        y += 11;
        List<Milestone.Cost> costs = new ArrayList<>();
        for (Milestone.Cost c : def.cost()) if (c.present()) costs.add(c);
        costs.sort(Comparator.comparing((Milestone.Cost c) -> BuildGunHud.have(c) >= c.count()));
        for (Milestone.Cost c : costs) {
            int have = BuildGunHud.have(c);
            g.item(c.icon(), x, y - 4);
            g.text(font, Component.literal(have + " / " + c.count() + " ").append(c.label()), x + 19, y, have >= c.count() ? WHITE : MISSING, false);
            y += 17;
        }
        y += 2;
        Component power = menuPower(def);
        g.text(font, Component.translatable("siftec.build_menu.power"), x, y, WHITE, false);
        g.text(font, power == null ? Component.translatable("siftec.build_menu.power_none") : power, x + 60, y, GREY, false);
        y += 12;
        int[] size = BuildingVisuals.footprint(def);
        g.text(font, Component.translatable("siftec.build_menu.footprint"), x, y, WHITE, false);
        if (size != null) g.text(font, Component.translatable("siftec.build_menu.footprint_value", size[0], size[1], size[2]), x + 60, y, GREY, false);
    }

    /** What it does to the grid, under the panel's own "Power" heading: the MW it uses or makes, or null for none. */
    private static @Nullable Component menuPower(BuildingDef def) {
        if (BuildGunHud.makesPower(def)) return Component.translatable("siftec.build_menu.power_make", BuildGunHud.mw(def.mw()));
        if (def.su() > 0) return Component.translatable("siftec.build_menu.power_use", BuildGunHud.mw(def.su() / BuildGunHud.SU_PER_MW));
        return null;
    }

    /** Its description: the first lines of its Ponder scene, Satisfactory's text for it. */
    private static Component description(BuildingDef def) {
        MutableComponent out = Component.empty();
        Language lang = Language.getInstance();
        for (int n = 1; n <= 3; n++) {
            String key = "siftec.ponder." + def.id() + ".text_" + n;
            if (!lang.has(key)) break;
            if (n > 1) out.append(" ");
            out.append(Component.translatable(key));
        }
        return out;
    }
}
