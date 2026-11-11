package com.meakaandre.siftec.prefab;

import com.meakaandre.siftec.block.Facing;
import com.meakaandre.siftec.registry.ModBlocks;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * The two structures, as small as their parts allow (the buildings' plain look: floor, posts, open sides, flat roofs;
 * the final looks are designed in game).
 * <p>
 * HUB: 9 wide, 15 long, 14 high with the FICSIT Freighter docked. From the front: the entrance step, the terminal hall
 * (HUB Terminal, Craft Station, Personal Storage Box), the living quarters (a bunk bed and lockers) and the burner deck
 * at the back (two burners either side of a walkway), with the freighter's dock mast and platform over the deck. Levels
 * are the HUB upgrades done (0 to 6); level 7 is upgrade 6 with the freighter docked.
 * <p>
 * Wormhole Gateway (the Space Elevator): 24 wide, 27 long and 22 high. It grows the way the Space Elevator does:
 * placed, it is a bare base platform with the delivery terminal and two power hookups; Phase 1 adds the ring's
 * foundation (a cradle) and the lower supports; Phase 2 the bottom third of a standing ring gate; Phase 3 the second
 * third; Phase 4 closes the ring and runs energy conduits into it; Phase 5 lights the ring's lining and opens a swirling
 * portal to The Sift inside it. An original sci-fi ring of Create blocks, with no chevrons or glyphs. Levels are the
 * phases done.
 */
public final class Templates {
    public static final int HUB_MAX = 7, GATEWAY_MAX = 5;
    /** Where the terminal stands in each structure. */
    public static final int[] HUB_TERMINAL = {0, 1, 3}, GATEWAY_TERMINAL = {0, 1, 4};
    /** Spots of the HUB's parts, for the tests. */
    public static final int[] STORAGE = {3, 1, 3}, BURNER_1 = {-2, 1, 9}, BURNER_2 = {3, 1, 9}, BED = {-3, 1, 6},
        TOWER = {0, 5, 14}, FREIGHTER = {0, 11, 12};
    /**
     * The Craft Station (Satisfactory's Craft Bench, Create's way): a Press over a Basin and a Mixer over a Basin on the
     * terminal hall's left, each turned by its own hand crank (the Mixer's through a cogwheel beside it).
     */
    public static final int[] PRESS_BASIN = {-3, 1, 2}, PRESS = {-3, 3, 2}, PRESS_CRANK = {-4, 3, 2},
        MIXER_BASIN = {-3, 1, 4}, MIXER = {-3, 3, 4}, MIXER_COG = {-2, 3, 4}, MIXER_CRANK = {-2, 4, 4};
    /**
     * The Wormhole Gateway's ring: a standing ring gate whose centre is at (-0.5, RING_Y) in the x-y plane, RING_Z deep in
     * the deck, from RING_INNER to RING_OUTER blocks out and three blocks thick. Its portal fills the inside.
     */
    public static final int RING_Y = 11, RING_Z = 15, RING_INNER = 7, RING_OUTER = 10, SUPPORT_X = 7;
    /** The power posts beside the gateway's terminal, each with a wire connector on top: x, connector height, z. */
    public static final int[][] POWER_POSTS = {{-3, 3, 4}, {2, 3, 4}};

    /** True if a spot (in the gateway's frame) is inside its portal, where walking in takes a player to The Sift. */
    public static boolean inPortal(double x, double y, double z) {
        double dx = x, dy = y - (RING_Y + 0.5);
        return Math.abs(z - (RING_Z + 0.5)) < 1.5 && dx * dx + dy * dy < (RING_INNER - 0.5) * (RING_INNER - 0.5);
    }

    /** One block of each part of the gateway, by phase (1 to 4), and of its lit lining and portal (5), for the tests. */
    public static int[][] gatewayMarks() {
        int bottom = RING_Y - RING_OUTER + 1, side = RING_Y, top = RING_Y + RING_OUTER - 1;
        return new int[][]{{-4, 1, RING_Z - 2}, {0, bottom, RING_Z}, {-RING_OUTER + 1, side, RING_Z}, {0, top, RING_Z}, {0, RING_Y - RING_INNER, RING_Z}};
    }
    private static Template hub, gateway;

    private Templates() {
    }

    private static BlockState s(String style) {
        return ModBlocks.PREFAB.get(style).get().defaultBlockState();
    }

    public static Template hub() {
        if (hub == null) hub = buildHub();
        return hub;
    }

    public static Template gateway() {
        if (gateway == null) gateway = buildGateway();
        return gateway;
    }

    private static Template buildHub() {
        Template t = new Template(HUB_MAX);
        int M = HUB_MAX;
        BlockState orange = s("orange"), metal = s("metal"), floor = s("floor"), step = s("step"), locker = s("locker"), air = Template.AIR;

        // ---- the platform: an entrance step at the front, the floor behind it
        t.box(0, M, -4, 0, 0, 4, 0, 0, step);
        t.box(0, M, -4, 0, 1, 4, 0, 14, floor);

        // ---- terminal hall (z 1 to 5): corner posts, open sides, a flat roof at y 5
        t.box(0, M, -4, 1, 1, 4, 4, 5, air);
        for (int[] c : new int[][]{{-4, 1}, {4, 1}, {-4, 5}, {4, 5}}) t.box(0, M, c[0], 1, c[1], c[0], 4, c[1], metal);
        t.box(0, M, -4, 5, 1, 4, 5, 5, orange);
        // the HUB Terminal
        t.set(0, M, HUB_TERMINAL[0], HUB_TERMINAL[1], HUB_TERMINAL[2], ModBlocks.HUB.get().defaultBlockState().setValue(Facing.FACING, Direction.SOUTH));
        // the Craft Station: by hand only, at the same amounts as the buildings
        t.set(0, M, PRESS_BASIN[0], PRESS_BASIN[1], PRESS_BASIN[2], create("basin"));
        t.set(0, M, PRESS[0], PRESS[1], PRESS[2], create("mechanical_press", BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        t.set(0, M, PRESS_CRANK[0], PRESS_CRANK[1], PRESS_CRANK[2], create("hand_crank", BlockStateProperties.FACING, Direction.WEST));
        t.set(0, M, MIXER_BASIN[0], MIXER_BASIN[1], MIXER_BASIN[2], create("basin"));
        t.set(0, M, MIXER[0], MIXER[1], MIXER[2], create("mechanical_mixer"));
        t.set(0, M, MIXER_COG[0], MIXER_COG[1], MIXER_COG[2], create("cogwheel", BlockStateProperties.AXIS, Direction.Axis.Y));
        t.set(0, M, MIXER_CRANK[0], MIXER_CRANK[1], MIXER_CRANK[2], create("hand_crank", BlockStateProperties.FACING, Direction.UP));
        // the Personal Storage Box (HUB Upgrade 1) between two counters
        t.set(0, M, STORAGE[0], STORAGE[1], STORAGE[2] - 1, metal);
        t.set(0, 0, STORAGE[0], STORAGE[1], STORAGE[2], air);
        t.set(1, M, STORAGE[0], STORAGE[1], STORAGE[2], ModBlocks.PERSONAL_STORAGE.get().defaultBlockState());
        t.set(0, M, STORAGE[0], STORAGE[1], STORAGE[2] + 1, metal);

        // ---- living quarters (z 6 to 8), from HUB Upgrade 4: a bunk bed and lockers under a roof at y 4
        t.box(4, M, -4, 1, 6, 4, 3, 8, air);
        for (int[] c : new int[][]{{-4, 6}, {4, 6}, {-4, 8}, {4, 8}}) t.box(4, M, c[0], 1, c[1], c[0], 3, c[1], metal);
        t.box(4, M, -4, 4, 6, 4, 4, 8, orange);
        for (int y = 1; y <= 2; y++) {
            t.set(4, M, BED[0], y, BED[2], bed(BedPart.FOOT));
            t.set(4, M, BED[0], y, BED[2] + 1, bed(BedPart.HEAD));
        }
        t.box(4, M, 3, 1, 6, 3, 2, 7, locker);

        // ---- burner deck (z 9 to 14): the burners from upgrades 2 and 5, the walkway between them
        burner(t, BURNER_1[0], 2);
        burner(t, BURNER_2[0], 5);

        // ---- the freighter's dock (HUB Upgrade 6): a mast at the back of the walkway up to a platform over the burners,
        // and the FICSIT Freighter itself (level 7: docked)
        t.box(6, M, TOWER[0], 1, TOWER[2], TOWER[0], 9, TOWER[2], metal);
        t.box(6, M, -2, 10, 10, 2, 10, 14, orange);
        freighter(t);
        return t;
    }


    private static BlockState create(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("create", id)).defaultBlockState();
    }

    /** A Create block for the Craft Station, with one property set (Create's own blocks use the vanilla properties). */
    private static <T extends Comparable<T>> BlockState create(String id, Property<T> property, T value) {
        BlockState state = create(id);
        return state.hasProperty(property) ? state.setValue(property, value) : state;
    }

    /** The Craft Station's spots, for the HUB to protect and the tests to look at. */
    public static int[][] station() {
        return new int[][]{PRESS_BASIN, PRESS, PRESS_CRANK, MIXER_BASIN, MIXER, MIXER_COG, MIXER_CRANK};
    }

    private static BlockState bed(BedPart part) {
        return ModBlocks.HUB_BED.get().defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, part);
    }

    /** A Create: New Age wire connector standing on the block below it. */
    private static BlockState connector() {
        BlockState state = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("create_new_age", "electrical_connector")).defaultBlockState();
        return state.hasProperty(BlockStateProperties.FACING) ? state.setValue(BlockStateProperties.FACING, Direction.UP) : state;
    }

    /**
     * A HUB Biomass Burner at x on the burner deck: nothing until its upgrade, then the same machine as the Biomass Burner
     * building (tools/structures.py, biomass_burner), standing open on the deck: at its back a Blaze Burner under a
     * four-high Fluid Tank (a closed boiler), its Steam Engine driving a shaft that turns a Create: New Age generator coil
     * ringed by four netherite magnets, the carbon brushes beside it. The HUB Burner block at its front takes the fuel and
     * feeds the Blaze Burner ({@link HubBurnerBlockEntity}); the wire connector stands on it. Cells are given as the Biomass
     * Burner's frame: x across from the burner block, y up from the deck, z back from the front.
     */
    private static void burner(Template t, int x, int from) {
        int M = HUB_MAX, front = BURNER_1[2];
        // before the upgrade: the deck is bare
        t.box(0, from - 1, x - 2, 1, front, x + 1, 5, front + 5, Template.AIR);
        // the fuel inlet and its wire connector
        t.set(from, M, x, 1, front, ModBlocks.HUB_BURNER.get().defaultBlockState().setValue(Facing.FACING, Direction.SOUTH));
        t.set(from, M, x, 2, front, connector());
        // the boiler: a Blaze Burner under a four-high tank, the Steam Engine on the tank's front
        for (int[] cell : HubBurnerBlockEntity.MACHINE) {
            t.set(from, M, x + cell[0], cell[1], front + cell[2], parse(HubBurnerBlockEntity.MACHINE_STATES[cell[3]]));
        }
    }


    /** A block state written as "namespace:id[property=value,...]", as the building templates write them. */
    static BlockState parse(String spec) {
        int open = spec.indexOf('[');
        String id = open < 0 ? spec : spec.substring(0, open);
        BlockState state = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id)).defaultBlockState();
        if (open < 0) return state;
        for (String pair : spec.substring(open + 1, spec.length() - 1).split(",")) {
            String[] kv = pair.split("=");
            var property = state.getBlock().getStateDefinition().getProperty(kv[0]);
            if (property != null) state = with(state, property, kv[1]);
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
    }

    private static void freighter(Template t) {
        int F = HUB_MAX;
        BlockState white = s("white"), orange = s("orange");
        t.box(F, F, -2, 11, 10, 2, 12, 14, white);
        t.box(F, F, -1, 11, 9, 1, 12, 9, orange);
        t.box(F, F, -2, 13, 13, 2, 13, 14, orange);
    }


    private static Template buildGateway() {
        Template t = new Template(GATEWAY_MAX);
        int M = GATEWAY_MAX;
        BlockState white = s("white"), orange = s("orange"), metal = s("metal"), frame = s("railway"), floor = s("floor"), lamp = s("lamp"),
            step = s("step"), coil = s("coil"), lit = s("coil_lit"), conduit = s("conduit"), portal = s("portal"), air = Template.AIR,
            hazardY = s("hazard_yellow"), hazardB = s("hazard_black");
        int x0 = -12, x1 = 11, z0 = 0, z1 = 26;

        // ---- placed (HUB Upgrade 6): the base platform, the delivery terminal and the power hookups. The deck's edge is
        // a band of hazard stripes inside an orange rim, as the Space Elevator's base has it
        t.box(0, M, x0, 0, z0, x1, 0, z1, floor);
        t.ring(0, M, x0, 0, z0, x1, 0, z1, orange);
        for (int x = x0 + 1; x <= x1 - 1; x++) {
            t.set(0, M, x, 0, z0 + 1, (x & 1) == 0 ? hazardY : hazardB);
            t.set(0, M, x, 0, z1 - 1, (x & 1) == 0 ? hazardY : hazardB);
        }
        for (int z = z0 + 2; z <= z1 - 2; z++) {
            t.set(0, M, x0 + 1, 0, z, (z & 1) == 0 ? hazardY : hazardB);
            t.set(0, M, x1 - 1, 0, z, (z & 1) == 0 ? hazardY : hazardB);
        }
        t.box(0, M, -3, 0, z0, 2, 0, z0, step);
        // a walkway from the terminal to the gate, and lamps along the edge
        t.box(0, M, -2, 0, 2, 1, 0, RING_Z - 4, white);
        t.box(0, M, -3, 0, 2, -3, 0, RING_Z - 4, metal);
        t.box(0, M, 2, 0, 2, 2, 0, RING_Z - 4, metal);
        for (int z = 4; z <= z1 - 4; z += 7) {
            t.set(0, M, x0, 0, z, lamp);
            t.set(0, M, x1, 0, z, lamp);
        }
        // the delivery terminal in a console, facing the way in
        t.set(0, M, GATEWAY_TERMINAL[0], GATEWAY_TERMINAL[1], GATEWAY_TERMINAL[2],
            ModBlocks.WORMHOLE_GATEWAY.get().defaultBlockState().setValue(Facing.FACING, Direction.SOUTH));
        t.box(0, M, -2, 1, GATEWAY_TERMINAL[2] + 1, 1, 2, GATEWAY_TERMINAL[2] + 1, metal);
        t.set(0, M, -1, 2, GATEWAY_TERMINAL[2] + 1, lamp);
        t.set(0, M, -2, 1, GATEWAY_TERMINAL[2], metal);
        t.set(0, M, 1, 1, GATEWAY_TERMINAL[2], metal);
        // power hookups: a post on each side of the terminal with a wire connector on top
        for (int[] post : POWER_POSTS) {
            t.box(0, M, post[0], 1, post[2], post[0], post[1] - 1, post[2], metal);
            t.set(0, M, post[0], 1, post[2], hazardY);
            t.set(0, M, post[0], post[1], post[2], connector());
        }
        // the cradle's outline, marked out on the deck before anything stands on it
        t.ring(0, M, -5, 0, RING_Z - 3, 4, 0, RING_Z + 3, orange);

        // ---- the gate: a standing ring in the x-y plane, RING_Z deep in the middle of the deck. Each block of the band
        // between the inner and the outer radius comes with the phase that builds its part of the ring, counted from the
        // bottom up both sides at once: Phase 2 the bottom third, Phase 3 the sides, Phase 4 the top, closing it.
        for (int x = -RING_OUTER - 1; x <= RING_OUTER; x++) {
            for (int y = RING_Y - RING_OUTER - 1; y <= RING_Y + RING_OUTER + 1; y++) {
                double dx = x + 0.5, dy = y - RING_Y, d = Math.sqrt(dx * dx + dy * dy);
                if (d < RING_INNER) {
                    // inside the ring: kept clear, and from Phase 5 the portal's swirl in its middle layer
                    t.box(0, 4, x, y, RING_Z - 1, x, y, RING_Z + 1, air);
                    t.box(5, M, x, y, RING_Z - 1, x, y, RING_Z + 1, air);
                    t.set(5, M, x, y, RING_Z, portal);
                    continue;
                }
                if (d >= RING_OUTER) continue;
                double angle = Math.toDegrees(Math.atan2(Math.abs(dx), -dy));   // 0 at the bottom, 180 at the top
                int phase = angle <= 60 ? 2 : angle <= 120 ? 3 : 4;
                boolean inner = d < RING_INNER + 1, outer = d >= RING_OUTER - 1;
                for (int z = RING_Z - 1; z <= RING_Z + 1; z++) {
                    BlockState body = inner && z == RING_Z ? coil : outer ? metal : z != RING_Z ? frame : metal;
                    if (inner && z == RING_Z) {
                        // the inner lining is dark until the gate lights up with Phase 5
                        t.set(phase, 4, x, y, z, coil);
                        t.set(5, M, x, y, z, lit);
                    } else {
                        t.set(phase, M, x, y, z, body);
                    }
                }
                // a broad orange band round the ring's faces, between its dark rims
                if (!inner && !outer && Math.abs(d - (RING_INNER + RING_OUTER) / 2.0) < 1.0) {
                    t.set(phase, M, x, y, RING_Z - 1, orange);
                    t.set(phase, M, x, y, RING_Z + 1, orange);
                }
            }
        }

        // ---- Phase 1: the ring's foundation, a cradle the ring's bottom sits in, and the lower supports either side
        int base = RING_Y - RING_OUTER + 1;   // the ring's lowest block
        t.box(1, M, -4, 1, RING_Z - 2, 3, base - 1, RING_Z + 2, metal);
        t.ring(1, M, -4, base - 1, RING_Z - 2, 3, base - 1, RING_Z + 2, orange);
        t.box(1, M, -3, 1, RING_Z - 3, 2, 1, RING_Z - 3, step);
        t.box(1, M, -3, 1, RING_Z + 3, 2, 1, RING_Z + 3, step);
        for (int x = -3; x <= 2; x++) {
            t.set(1, M, x, 1, RING_Z - 2, (x & 1) == 0 ? hazardY : hazardB);
            t.set(1, M, x, 1, RING_Z + 2, (x & 1) == 0 ? hazardY : hazardB);
        }
        for (int side : new int[]{-1, 1}) {
            int x = side < 0 ? -SUPPORT_X - 1 : SUPPORT_X;
            int top = supportTop(x);
            // an A-frame: a pylon fore and aft of the ring, braced together at the top
            for (int z : new int[]{RING_Z - 2, RING_Z + 2}) {
                t.box(1, M, x, 1, z, x, top, z, metal);
                t.set(1, M, x, 1, z, hazardY);
                t.set(1, M, x, 2, z, hazardB);
                for (int y = 4; y < top; y += 3) t.set(1, M, x, y, z, orange);
            }
            t.box(1, M, x, top, RING_Z - 2, x, top, RING_Z + 2, frame);
            t.box(1, M, x - side, 1, RING_Z - 2, x - side, 1, RING_Z + 2, metal);
            t.set(1, M, x, 1, RING_Z - 3, lamp);
            t.set(1, M, x, 1, RING_Z + 3, lamp);
        }

        // ---- Phase 4: energy conduits from the power hookups along the deck, into the cradle and up both supports
        for (int[] post : POWER_POSTS) {
            int x = post[0];
            t.box(4, M, x, 1, post[2] + 1, x, 1, RING_Z - 3, conduit);
        }
        t.box(4, M, POWER_POSTS[0][0], 1, RING_Z - 3, POWER_POSTS[1][0], 1, RING_Z - 3, conduit);
        for (int side : new int[]{-1, 1}) {
            int x = side < 0 ? -SUPPORT_X - 1 : SUPPORT_X;
            t.box(4, M, x + side, 1, RING_Z, x + side, supportTop(x) + 1, RING_Z, conduit);
            t.box(4, M, x - side, 2, RING_Z, x - side, supportTop(x), RING_Z, conduit);
        }

        // ---- Phase 5: the gate lights up (the lining above, the portal inside it) and the deck's lamps ring the gate
        for (int x = -6; x <= 5; x += 11) {
            t.set(5, M, x, 1, RING_Z - 4, lamp);
            t.set(5, M, x, 1, RING_Z + 4, lamp);
        }
        return t;
    }

    /** How high a support at x reaches: just under the ring's outer edge. */
    private static int supportTop(int x) {
        double dx = x + 0.5;
        return (int) Math.floor(RING_Y - Math.sqrt(Math.max(0, RING_OUTER * RING_OUTER - dx * dx)));
    }
}
