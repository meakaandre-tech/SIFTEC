package com.meakaandre.siftec.building;

import org.jspecify.annotations.Nullable;

/**
 * The AWESOME Shop's floor patterns, painted on a foundation's 8 by 8 top: what each cell of the top becomes (yellow,
 * black or white concrete), or null where the floor stays as it is. Cells are counted across (x) and along (z) the
 * foundation as built facing north.
 */
public final class Patterns {
    private static final String YELLOW = "minecraft:yellow_concrete", BLACK = "minecraft:black_concrete", WHITE = "minecraft:white_concrete";

    /** The Arrow (pointing away from the front), the Factory Icon (a FICSIT gear), the Transportation Icon (a truck), the Number (a 1). */
    private static final String[] ARROW = {
        "...##...",
        "..####..",
        ".######.",
        "...##...",
        "...##...",
        "...##...",
        "...##...",
        "........"};
    private static final String[] FACTORY = {
        "...##...",
        ".######.",
        ".##..##.",
        "###..###",
        "###..###",
        ".##..##.",
        ".######.",
        "...##..."};
    private static final String[] TRUCK = {
        "........",
        "#####...",
        "#####.#.",
        "#####.##",
        "########",
        "########",
        ".#....#.",
        "........"};
    private static final String[] NUMBER = {
        "...##...",
        "..###...",
        "...##...",
        "...##...",
        "...##...",
        "...##...",
        "..####..",
        "........"};

    private Patterns() {
    }

    public static @Nullable String at(String pattern, int x, int z) {
        if (x < 0 || x > 7 || z < 0 || z > 7) return null;
        return switch (pattern) {
            // a walkway: yellow edges down both sides
            case "pathway" -> x == 0 || x == 7 ? YELLOW : null;
            // hazard stripes all over
            case "factory_zone" -> Math.floorMod(x + z, 4) < 2 ? YELLOW : BLACK;
            case "dotted_line" -> (x == 3 || x == 4) && z % 4 < 2 ? YELLOW : null;
            case "solid_line" -> x == 3 || x == 4 ? YELLOW : null;
            case "arrow" -> picture(ARROW, x, z, YELLOW);
            case "factory_icon" -> picture(FACTORY, x, z, YELLOW);
            case "transportation_icon" -> picture(TRUCK, x, z, WHITE);
            case "number" -> picture(NUMBER, x, z, WHITE);
            default -> null;
        };
    }

    private static @Nullable String picture(String[] rows, int x, int z, String ink) {
        return rows[z].charAt(x) == '#' ? ink : null;
    }
}
