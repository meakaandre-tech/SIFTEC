package com.meakaandre.siftec.building;

import org.jspecify.annotations.Nullable;

/**
 * The AWESOME Shop's floor patterns, painted on a foundation's 8 by 8 top: what each cell of the top becomes (yellow,
 * black or white concrete), or null where the floor stays as it is. Cells are counted across (x) and along (z) the
 * foundation as built facing north.
 */
public final class Patterns {
    private static final String YELLOW = "minecraft:yellow_concrete", BLACK = "minecraft:black_concrete", WHITE = "minecraft:white_concrete";
    /** Cells each way across a foundation's top that a pattern paints. */
    public static final int SIZE = 8;

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

    /** A floor pattern the AWESOME Shop sells, by its id ("pattern:<id>"), with what it paints at each cell of the top. */
    public enum Pattern {
        // a walkway: yellow edges down both sides
        PATHWAY("pathway", (x, z) -> x == 0 || x == 7 ? YELLOW : null),
        // hazard stripes all over
        FACTORY_ZONE("factory_zone", (x, z) -> Math.floorMod(x + z, 4) < 2 ? YELLOW : BLACK),
        DOTTED_LINE("dotted_line", (x, z) -> (x == 3 || x == 4) && z % 4 < 2 ? YELLOW : null),
        SOLID_LINE("solid_line", (x, z) -> x == 3 || x == 4 ? YELLOW : null),
        ARROW("arrow", (x, z) -> picture(Patterns.ARROW, x, z, YELLOW)),
        FACTORY_ICON("factory_icon", (x, z) -> picture(FACTORY, x, z, YELLOW)),
        TRANSPORTATION_ICON("transportation_icon", (x, z) -> picture(TRUCK, x, z, WHITE)),
        NUMBER("number", (x, z) -> picture(Patterns.NUMBER, x, z, WHITE));

        private final String id;
        private final Ink ink;

        Pattern(String id, Ink ink) {
            this.id = id;
            this.ink = ink;
        }

        public String id() {
            return id;
        }

        public static Pattern of(String id) {
            Pattern p = find(id);
            if (p == null) throw new IllegalArgumentException("unknown floor pattern \"" + id + "\"");
            return p;
        }

        /** The pattern with this id, or null if there is none (a paint may name one this version does not know). */
        public static @Nullable Pattern find(String id) {
            for (Pattern p : values()) if (p.id.equals(id)) return p;
            return null;
        }

        /** What a cell of the top (0 to 7 each way) becomes, or null where the floor stays as it is. */
        public @Nullable String at(int x, int z) {
            if (x < 0 || x > SIZE - 1 || z < 0 || z > SIZE - 1) return null;
            return ink.at(x, z);
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /** What one pattern paints at a cell inside the top. */
    @FunctionalInterface
    private interface Ink {
        @Nullable String at(int x, int z);
    }

    /** What a cell of the top becomes under the pattern with this id (null for none, or an id this version does not know). */
    public static @Nullable String at(String pattern, int x, int z) {
        Pattern p = Pattern.find(pattern);
        return p == null ? null : p.at(x, z);
    }

    private static @Nullable String picture(String[] rows, int x, int z, String ink) {
        return rows[z].charAt(x) == '#' ? ink : null;
    }
}
