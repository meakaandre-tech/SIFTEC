package com.meakaandre.siftec.client.map;

import com.meakaandre.siftec.Siftec;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * The map's icons, drawn from small pixel glyphs into one texture when first needed (no image files): white shapes
 * with a dark outline, tinted when drawn. Each glyph is 9 by 9 in a cell of 11 by 11.
 */
public final class MapIcons {
    public static final int CELL = 11, SIZE = 9;
    private static final int COLUMNS = 16;
    private static final Identifier ID = Siftec.id("map/icons");
    private static boolean made;

    // marker icons (MapMarker.ICONS of them, in this order)
    public static final int FLAG = 0, STAR = 1, HOME = 2, PICK = 3, HEART = 4, TREE = 5, CROSS = 6, WARN = 7, DOT = 8, EYE = 9;
    // everything else
    public static final int SKULL = 10, NODE = 11, DROP = 12, GEYSER = 13, POD = 14, SPHERE = 15, LOOP = 16, HUB = 17,
        GATE = 18, RADAR = 19, TRAIN = 20, TRUCK = 21, DRONE = 22, ARROW = 23, DISC = 24, RING = 25, PIN = 26, SQUARE = 27;

    private static final String[][] GLYPHS = {
        {"#........", "#######..", "########.", "#######..", "######...", "#........", "#........", "#........", "#........"},
        {"....#....", "....#....", "...###...", "#########", ".#######.", "..#####..", "..##.##..", ".##...##.", ".#.....#."},
        {"....#....", "...###...", "..#####..", ".#######.", "#########", ".##...##.", ".##.#.##.", ".##.#.##.", ".#######."},
        {"..#####..", ".#.###.#.", "#...#...#", "....#....", "....#....", "....#....", "....#....", "....#....", "...###..."},
        {".##...##.", "####.####", "#########", "#########", ".#######.", "..#####..", "...###...", "....#....", "........."},
        {"....#....", "...###...", "..#####..", "...###...", "..#####..", ".#######.", "#########", "....#....", "...###..."},
        {"##.....##", "###...###", ".###.###.", "..#####..", "...###...", "..#####..", ".###.###.", "###...###", "##.....##"},
        {"....#....", "...###...", "...#.#...", "..##.##..", "..##.##..", ".#######.", ".###.###.", "#########", "#########"},
        {"...###...", ".#######.", ".#######.", "#########", "#########", "#########", ".#######.", ".#######.", "...###..."},
        {"...###...", ".##...##.", "#...#...#", "#..###..#", "#.#####.#", "#..###..#", "#...#...#", ".##...##.", "...###..."},
        {"..#####..", ".#######.", "##.###.##", "#...#...#", "##.###.##", ".#######.", "..#.#.#..", "..#####..", "...###..."},
        {"....#....", "...###...", "..#####..", ".#######.", "#########", ".#######.", "..#####..", "...###...", "....#...."},
        {"....#....", "....#....", "...###...", "..#####..", ".#######.", ".#######.", ".#######.", "..#####..", "...###..."},
        {"#..#..#..", ".#..#..#.", "#..#..#..", ".#..#..#.", "....#....", "...###...", "..#####..", ".#######.", "#########"},
        {"...###...", "..#...#..", ".#.###.#.", ".#.###.#.", ".#.###.#.", ".#.###.#.", ".#######.", "#..#.#..#", "#.......#"},
        {"...###...", "..#####..", ".###.###.", ".##...##.", ".##...##.", ".###.###.", "..#####..", "...###...", "........."},
        {"..##.##..", ".#..#..#.", "#...#...#", "#..#.#..#", ".##...##.", "#..#.#..#", "#...#...#", ".#..#..#.", "..##.##.."},
        {"#.......#", "#.......#", "#.......#", "#########", "#########", "#.......#", "#.......#", "#.......#", "#.......#"},
        {"...###...", ".##...##.", ".#.....#.", "#.......#", "#.......#", "#.......#", ".#.....#.", ".##...##.", "...###..."},
        {"#........", ".#.......", "..#..###.", "...##..#.", "...##..#.", "..#.##...", ".#...##..", "#.....##.", "......###"},
        {".#######.", "#########", "##.###.##", "#########", "#########", ".#######.", "..#...#..", ".#.....#.", "#.......#"},
        {".......##", "#######.#", "#######.#", "#######.#", "#########", "#########", "#########", ".##...##.", ".##...##."},
        {"##.....##", "##.....##", "..#...#..", "...###...", "...###...", "...###...", "..#...#..", "##.....##", "##.....##"},
        {"....#....", "....#....", "...###...", "...###...", "..#####..", "..#####..", ".#######.", ".###.###.", "##.....##"},
        {"..#####..", ".#######.", "#########", "#########", "#########", "#########", "#########", ".#######.", "..#####.."},
        {"..#####..", ".##...##.", "##.....##", "#.......#", "#.......#", "#.......#", "##.....##", ".##...##.", "..#####.."},
        {"..#####..", ".#######.", ".#######.", ".#######.", "..#####..", "...###...", "....#....", "....#....", "....#...."},
        {"#########", "#########", "#########", "#########", "#########", "#########", "#########", "#########", "#########"},
    };

    private MapIcons() {
    }

    private static void make() {
        if (made) return;
        made = true;
        int rows = (GLYPHS.length + COLUMNS - 1) / COLUMNS;
        NativeImage image = new NativeImage(COLUMNS * CELL, rows * CELL, true);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) image.setPixel(x, y, 0);
        for (int i = 0; i < GLYPHS.length; i++) {
            int ox = (i % COLUMNS) * CELL, oy = (i / COLUMNS) * CELL;
            String[] g = GLYPHS[i];
            // the outline first: every clear pixel next to a set one
            for (int y = -1; y <= SIZE; y++) {
                for (int x = -1; x <= SIZE; x++) {
                    if (set(g, x, y)) continue;
                    boolean edge = false;
                    for (int dy = -1; dy <= 1 && !edge; dy++) for (int dx = -1; dx <= 1 && !edge; dx++) edge = set(g, x + dx, y + dy);
                    if (edge) image.setPixel(ox + 1 + x, oy + 1 + y, 0xB0101010);
                }
            }
            for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) if (set(g, x, y)) image.setPixel(ox + 1 + x, oy + 1 + y, 0xFFFFFFFF);
        }
        Minecraft.getInstance().getTextureManager().register(ID, new DynamicTexture(() -> "siftec map icons", image));
    }

    private static boolean set(String[] g, int x, int y) {
        return x >= 0 && y >= 0 && x < SIZE && y < SIZE && g[y].charAt(x) == '#';
    }

    /** Draws an icon with its top-left corner at x, y (the outline reaches a pixel further), tinted ARGB. */
    public static void draw(GuiGraphicsExtractor g, int icon, int x, int y, int argb) {
        make();
        int u = (icon % COLUMNS) * CELL, v = (icon / COLUMNS) * CELL;
        int texW = COLUMNS * CELL, texH = ((GLYPHS.length + COLUMNS - 1) / COLUMNS) * CELL;
        g.blit(RenderPipelines.GUI_TEXTURED, ID, x - 1, y - 1, u, v, CELL, CELL, texW, texH, argb);
    }

    /** Draws an icon centred on x, y. */
    public static void centred(GuiGraphicsExtractor g, int icon, int x, int y, int argb) {
        draw(g, icon, x - SIZE / 2, y - SIZE / 2, argb);
    }

    /** Draws an icon centred on x, y, turned by an angle (radians, clockwise on screen). */
    public static void turned(GuiGraphicsExtractor g, int icon, float x, float y, float radians, int argb) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().rotate(radians);
        draw(g, icon, -SIZE / 2, -SIZE / 2, argb);
        g.pose().popMatrix();
    }
}
