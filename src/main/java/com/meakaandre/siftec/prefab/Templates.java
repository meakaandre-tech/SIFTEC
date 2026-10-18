package com.meakaandre.siftec.prefab;

import com.meakaandre.siftec.block.Facing;
import com.meakaandre.siftec.registry.ModBlocks;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * The two structures, at Satisfactory's size with the player 1.8 blocks tall (one metre is one block).
 * <p>
 * HUB: 14 wide, 26 long, 28 high with the FICSIT Freighter docked (the wiki's 14 x 26 x 28 m). From the front: the
 * entrance steps, the terminal hall (HUB Terminal, Craft Station, Personal Storage Box), the living quarters (bunk
 * beds, lockers, desk, washroom) and the burner deck at the back (two burners), with the freighter's dock tower
 * over the deck. Levels are the HUB upgrades done (0 to 6); level 7 is upgrade 6 with the freighter docked.
 * <p>
 * Wormhole Gateway (the Space Elevator): 48 wide and 54 long like the Space Elevator (48 x 54 m), and 42 high. It
 * grows the way the Space Elevator does: placed, it is a bare base platform with the delivery terminal and two power
 * hookups; Phase 1 adds the ring's foundation (a cradle) and the lower supports; Phase 2 the bottom third of a huge
 * standing ring gate; Phase 3 the second third; Phase 4 closes the ring and runs energy conduits into it; Phase 5 lights
 * the ring's lining and opens a swirling portal to The Sift inside it. An original sci-fi ring of Create blocks, with no
 * chevrons or glyphs. Levels are the phases done.
 */
public final class Templates {
    public static final int HUB_MAX = 7, GATEWAY_MAX = 5;
    /** Where the terminal stands in each structure. */
    public static final int[] HUB_TERMINAL = {0, 1, 5}, GATEWAY_TERMINAL = {0, 1, 7};
    /** Spots of the HUB's parts, for the tests. */
    public static final int[] STORAGE = {3, 1, 4}, BURNER_1 = {-4, 1, 19}, BURNER_2 = {3, 1, 19}, BED = {-6, 1, 12},
        TOWER = {-1, 10, 22}, FREIGHTER = {0, 22, 20};
    /**
     * The Craft Station (Satisfactory's Craft Bench, Create's way): a Press over a Basin and a Mixer over a Basin on the
     * terminal hall's left, each turned by its own hand crank (the Mixer's through a cogwheel beside it).
     */
    public static final int[] PRESS_BASIN = {-5, 1, 4}, PRESS = {-5, 3, 4}, PRESS_CRANK = {-6, 3, 4},
        MIXER_BASIN = {-3, 1, 4}, MIXER = {-3, 3, 4}, MIXER_COG = {-2, 3, 4}, MIXER_CRANK = {-2, 4, 4};
    /**
     * The Wormhole Gateway's ring: a standing ring gate whose centre is at (-0.5, RING_Y) in the x-y plane, RING_Z deep in
     * the deck, from RING_INNER to RING_OUTER blocks out and three blocks thick. Its portal fills the inside.
     */
    public static final int RING_Y = 22, RING_Z = 30, RING_INNER = 14, RING_OUTER = 19, SUPPORT_X = 14;
    /** The power posts beside the gateway's terminal, each with a wire connector on top: x, connector height, z. */
    public static final int[][] POWER_POSTS = {{-5, 3, 6}, {4, 3, 6}};

    /** True if a spot (in the gateway's frame) is inside its portal, where walking in takes a player to The Sift. */
    public static boolean inPortal(double x, double y, double z) {
        double dx = x, dy = y - (RING_Y + 0.5);
        return Math.abs(z - (RING_Z + 0.5)) < 1.5 && dx * dx + dy * dy < (RING_INNER - 0.5) * (RING_INNER - 0.5);
    }

    /** One block of each part of the gateway, by phase (1 to 4), and of its lit lining and portal (5), for the tests. */
    public static int[][] gatewayMarks() {
        int bottom = RING_Y - RING_OUTER + 1, side = RING_Y, top = RING_Y + RING_OUTER - 1;
        return new int[][]{{-8, 1, RING_Z - 3}, {0, bottom, RING_Z}, {-RING_OUTER + 1, side, RING_Z}, {0, top, RING_Z}, {0, RING_Y - RING_INNER, RING_Z}};
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
        BlockState white = s("white"), orange = s("orange"), metal = s("metal"), floor = s("floor"), glass = s("glass"),
            lamp = s("lamp"), step = s("step"), locker = s("locker"), air = Template.AIR;

        // ---- the platform: entrance steps at the front, the floor, yellow and black hazard stripes down its sides (as
        // Polartt's pads have them) and an orange back edge
        t.box(0, M, -7, 0, 0, 6, 0, 0, step);
        t.box(0, M, -7, 0, 1, 6, 0, 25, floor);
        BlockState hazardY = s("hazard_yellow"), hazardB = s("hazard_black");
        for (int z = 1; z <= 25; z++) {
            t.set(0, M, -7, 0, z, z % 2 == 0 ? hazardY : hazardB);
            t.set(0, M, 6, 0, z, z % 2 == 0 ? hazardY : hazardB);
        }
        t.box(0, M, -7, 0, 25, 6, 0, 25, orange);

        // ---- terminal hall (z 1 to 9), open at the front, roof at y 7
        t.box(0, M, -6, 1, 1, 5, 5, 8, air);
        for (int[] c : new int[][]{{-7, 1}, {6, 1}, {-7, 9}, {6, 9}}) t.box(0, M, c[0], 1, c[1], c[0], 6, c[1], metal);
        for (int x : new int[]{-7, 6}) {
            t.box(0, M, x, 1, 2, x, 1, 8, white);
            t.box(0, M, x, 2, 2, x, 4, 8, glass);
            t.box(0, M, x, 5, 2, x, 6, 8, white);
        }
        t.box(0, M, -6, 1, 9, 5, 6, 9, white);
        t.box(0, M, -5, 3, 9, -3, 4, 9, glass);
        t.box(0, M, 2, 3, 9, 4, 4, 9, glass);
        t.box(0, M, -1, 1, 9, 0, 3, 9, air);
        t.box(0, M, -6, 6, 1, 5, 6, 1, orange);
        t.box(0, M, -7, 7, 1, 6, 7, 9, white);
        t.box(0, M, -7, 7, 1, 6, 7, 1, orange);
        for (int[] l : new int[][]{{-4, 3}, {3, 3}, {-4, 7}, {3, 7}, {0, 2}}) t.set(0, M, l[0], 7, l[1], lamp);
        // the HUB Terminal and its console
        t.set(0, M, HUB_TERMINAL[0], HUB_TERMINAL[1], HUB_TERMINAL[2], ModBlocks.HUB.get().defaultBlockState().setValue(Facing.FACING, Direction.SOUTH));
        t.box(0, M, -1, 1, 6, 1, 2, 6, metal);
        t.set(0, M, 0, 2, 6, lamp);
        t.box(0, M, -1, 3, 6, 1, 3, 6, orange);
        // the Craft Station: by hand only, at the same amounts as the buildings
        t.set(0, M, PRESS_BASIN[0], PRESS_BASIN[1], PRESS_BASIN[2], create("basin"));
        t.set(0, M, PRESS[0], PRESS[1], PRESS[2], create("mechanical_press", BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        t.set(0, M, PRESS_CRANK[0], PRESS_CRANK[1], PRESS_CRANK[2], create("hand_crank", BlockStateProperties.FACING, Direction.WEST));
        t.set(0, M, -4, 1, 4, metal);
        t.set(0, M, MIXER_BASIN[0], MIXER_BASIN[1], MIXER_BASIN[2], create("basin"));
        t.set(0, M, MIXER[0], MIXER[1], MIXER[2], create("mechanical_mixer"));
        t.set(0, M, MIXER_COG[0], MIXER_COG[1], MIXER_COG[2], create("cogwheel", BlockStateProperties.AXIS, Direction.Axis.Y));
        t.set(0, M, MIXER_CRANK[0], MIXER_CRANK[1], MIXER_CRANK[2], create("hand_crank", BlockStateProperties.FACING, Direction.UP));
        // the Personal Storage Box (HUB Upgrade 1) between two counters
        t.set(0, M, 2, 1, 4, metal);
        t.set(0, 0, STORAGE[0], STORAGE[1], STORAGE[2], air);
        t.set(1, M, STORAGE[0], STORAGE[1], STORAGE[2], ModBlocks.PERSONAL_STORAGE.get().defaultBlockState());
        t.set(0, M, 4, 1, 4, metal);

        // ---- living quarters (z 10 to 17), from HUB Upgrade 4: before that the floor is bare
        t.box(4, M, -6, 1, 10, 5, 5, 16, air);
        for (int x : new int[]{-7, 6}) {
            t.box(4, M, x, 1, 10, x, 1, 16, white);
            t.box(4, M, x, 2, 10, x, 3, 16, glass);
            t.box(4, M, x, 4, 10, x, 5, 16, white);
            t.box(4, M, x, 1, 17, x, 6, 17, metal);
        }
        t.box(4, M, -6, 1, 17, 5, 5, 17, white);
        t.box(4, M, -1, 1, 17, 0, 3, 17, air);
        t.box(4, M, -7, 6, 10, 6, 6, 16, white);
        t.box(4, M, -6, 6, 17, 5, 6, 17, orange);
        t.set(4, M, -3, 6, 13, lamp);
        t.set(4, M, 2, 6, 13, lamp);
        // two bunk beds against the left wall
        for (int z : new int[]{11, 14}) {
            for (int y = 1; y <= 2; y++) {
                t.set(4, M, -6, y, z, bed(BedPart.FOOT));
                t.set(4, M, -6, y, z + 1, bed(BedPart.HEAD));
            }
        }
        // lockers and a desk on the right; the washroom in the back corner
        t.box(4, M, 5, 1, 11, 5, 2, 12, locker);
        t.set(4, M, 5, 1, 14, metal);
        t.set(4, M, 4, 1, 14, step);
        t.box(4, M, 3, 1, 16, 3, 3, 16, white);
        t.box(4, M, 4, 3, 15, 5, 3, 15, white);
        t.set(4, M, 5, 1, 16, step);

        // ---- burner deck (z 18 to 25): a low rail, a pad for each burner, the burners from upgrades 2 and 5
        t.box(0, M, -7, 1, 18, -7, 1, 25, metal);
        t.box(0, M, 6, 1, 18, 6, 1, 25, metal);
        t.box(0, M, -6, 1, 25, 5, 1, 25, metal);
        burner(t, BURNER_1[0], 2);
        burner(t, BURNER_2[0], 5);

        // ---- the freighter's dock (HUB Upgrade 6) and the FICSIT Freighter itself (level 7: docked)
        t.box(6, M, -1, 1, 21, 0, 19, 24, metal);
        for (int y : new int[]{5, 10, 15}) t.box(6, M, -1, y, 21, 0, y, 21, lamp);
        t.box(6, M, -3, 20, 19, 2, 20, 25, orange);
        t.box(6, M, -2, 20, 20, 1, 20, 24, white);
        freighter(t);
        return t;
    }

    private static BlockState create(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath("create", id)).defaultBlockState();
    }

    /** A Create block for the Craft Station, with one property set (Create's own blocks use the vanilla properties). */
    private static <T extends Comparable<T>> BlockState create(String id, net.minecraft.world.level.block.state.properties.Property<T> property, T value) {
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
        BlockState state = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath("create_new_age", "electrical_connector")).defaultBlockState();
        return state.hasProperty(BlockStateProperties.FACING) ? state.setValue(BlockStateProperties.FACING, Direction.UP) : state;
    }

    /**
     * A HUB Biomass Burner at x on the burner deck: nothing until its upgrade, then the same machine as the Biomass Burner
     * building (tools/structures.py, biomass_burner), fitted on the deck: an orange housing with dark corners and glass,
     * at its back a Blaze Burner under a four-high Fluid Tank (a closed boiler), its Steam Engine driving a shaft that
     * turns a Create: New Age generator coil ringed by four netherite magnets, the carbon brushes beside it. The HUB
     * Burner block in the front wall takes the fuel and feeds the Blaze Burner ({@link HubBurnerBlockEntity}); the wire
     * connector stands on it. Cells are given as the Biomass Burner's frame: x across from the burner block, y up from the
     * deck, z back from the front wall.
     */
    private static void burner(Template t, int x, int from) {
        int M = HUB_MAX, front = BURNER_1[2];
        BlockState air = Template.AIR, orange = s("orange"), dark = s("metal"), light = s("white"), glass = s("glass");
        // before the upgrade: the pad is bare
        t.box(0, from - 1, x - 2, 1, front, x + 2, 9, front + 5, air);
        t.set(0, M, x, 0, front + 2, orange);
        // the housing: dark corners, orange sides, glass on the sides and front, a checkered roof
        for (int fx = -2; fx <= 2; fx++) {
            for (int fz = 0; fz <= 5; fz++) {
                boolean edgeX = fx == -2 || fx == 2, edgeZ = fz == 0 || fz == 5;
                for (int y = 1; y <= 8; y++) {
                    BlockState wall = edgeX && edgeZ ? dark : edgeX || edgeZ ? orange : air;
                    t.set(from, M, x + fx, y, front + fz, wall);
                }
                t.set(from, M, x + fx, 9, front + fz, (fx + fz) % 2 == 0 ? light : dark);
            }
        }
        t.box(from, M, x + 2, 2, front + 1, x + 2, 4, front + 3, glass);
        t.box(from, M, x - 1, 2, front, x + 1, 3, front, glass);
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
        BlockState state = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(id)).defaultBlockState();
        if (open < 0) return state;
        for (String pair : spec.substring(open + 1, spec.length() - 1).split(",")) {
            String[] kv = pair.split("=");
            var property = state.getBlock().getStateDefinition().getProperty(kv[0]);
            if (property != null) state = with(state, property, kv[1]);
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState state, net.minecraft.world.level.block.state.properties.Property<T> property, String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
    }

    private static void freighter(Template t) {
        int F = HUB_MAX;
        BlockState white = s("white"), orange = s("orange"), glass = s("glass"), lamp = s("lamp");
        t.box(F, F, -2, 21, 14, 1, 21, 25, white);
        t.box(F, F, -3, 22, 13, 2, 23, 25, white);
        t.box(F, F, -3, 22, 14, -3, 22, 25, orange);
        t.box(F, F, 2, 22, 14, 2, 22, 25, orange);
        t.box(F, F, -2, 22, 12, 1, 23, 13, glass);
        t.box(F, F, -2, 24, 15, 1, 24, 24, white);
        t.box(F, F, -1, 25, 21, 0, 27, 24, orange);
        t.box(F, F, -2, 22, 25, 1, 23, 25, lamp);
        t.box(F, F, -6, 22, 18, -4, 22, 22, orange);
        t.box(F, F, 3, 22, 18, 5, 22, 22, orange);
    }

    private static Template buildGateway() {
        Template t = new Template(GATEWAY_MAX);
        int M = GATEWAY_MAX;
        BlockState white = s("white"), orange = s("orange"), metal = s("metal"), frame = s("railway"), floor = s("floor"), lamp = s("lamp"),
            step = s("step"), coil = s("coil"), lit = s("coil_lit"), conduit = s("conduit"), portal = s("portal"), air = Template.AIR;
        int x0 = -24, x1 = 23, z0 = 0, z1 = 53;

        // ---- placed (HUB Upgrade 6): the base platform, the delivery terminal and the power hookups
        t.box(0, M, x0, 0, z0, x1, 0, z1, floor);
        t.ring(0, M, x0, 0, z0, x1, 0, z1, orange);
        t.box(0, M, -6, 0, z0, 5, 0, z0, step);
        // a walkway from the terminal to the gate, and lamps along the edge
        t.box(0, M, -2, 0, 1, 1, 0, RING_Z - 4, white);
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
            t.set(0, M, post[0], post[1], post[2], connector());
        }
        // the cradle's outline, marked out on the deck before anything stands on it
        t.ring(0, M, -9, 0, RING_Z - 4, 8, 0, RING_Z + 4, orange);

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
                    BlockState body = inner && z == RING_Z ? coil : outer || z != RING_Z ? frame : metal;
                    if (inner && z == RING_Z) {
                        // the inner lining is dark until the gate lights up with Phase 5
                        t.set(phase, 4, x, y, z, coil);
                        t.set(5, M, x, y, z, lit);
                    } else {
                        t.set(phase, M, x, y, z, body);
                    }
                }
                // an orange band round the ring's faces, halfway through it
                if (!inner && !outer && Math.abs(d - (RING_INNER + RING_OUTER) / 2.0) < 0.5) {
                    t.set(phase, M, x, y, RING_Z - 1, orange);
                    t.set(phase, M, x, y, RING_Z + 1, orange);
                }
            }
        }

        // ---- Phase 1: the ring's foundation, a cradle the ring's bottom sits in, and the lower supports either side
        int base = RING_Y - RING_OUTER + 1;   // the ring's lowest block
        t.box(1, M, -8, 1, RING_Z - 3, 7, base - 1, RING_Z + 3, metal);
        t.ring(1, M, -8, base - 1, RING_Z - 3, 7, base - 1, RING_Z + 3, orange);
        t.box(1, M, -6, 1, RING_Z - 4, 5, 1, RING_Z - 4, step);
        t.box(1, M, -6, 1, RING_Z + 4, 5, 1, RING_Z + 4, step);
        for (int side : new int[]{-1, 1}) {
            int x = side < 0 ? -SUPPORT_X - 1 : SUPPORT_X;
            int top = supportTop(x);
            // an A-frame: a pylon fore and aft of the ring, braced together at the top
            for (int z : new int[]{RING_Z - 3, RING_Z + 3}) t.box(1, M, x, 1, z, x, top, z, frame);
            t.box(1, M, x, top, RING_Z - 3, x, top, RING_Z + 3, frame);
            t.box(1, M, x - side, 1, RING_Z - 3, x - side, 1, RING_Z + 3, metal);
            t.set(1, M, x, 1, RING_Z - 4, lamp);
            t.set(1, M, x, 1, RING_Z + 4, lamp);
        }

        // ---- Phase 4: energy conduits from the power hookups along the deck, into the cradle and up both supports
        for (int[] post : POWER_POSTS) {
            int x = post[0];
            t.box(4, M, x, 1, post[2] + 1, x, 1, RING_Z - 4, conduit);
        }
        t.box(4, M, POWER_POSTS[0][0], 1, RING_Z - 4, POWER_POSTS[1][0], 1, RING_Z - 4, conduit);
        for (int side : new int[]{-1, 1}) {
            int x = side < 0 ? -SUPPORT_X - 1 : SUPPORT_X;
            t.box(4, M, x + side, 1, RING_Z, x + side, supportTop(x) + 1, RING_Z, conduit);
            t.box(4, M, x - side, 2, RING_Z, x - side, supportTop(x), RING_Z, conduit);
        }

        // ---- Phase 5: the gate lights up (the lining above, the portal inside it) and the deck's lamps ring the gate
        for (int x = -10; x <= 9; x += 19) {
            t.set(5, M, x, 1, RING_Z - 5, lamp);
            t.set(5, M, x, 1, RING_Z + 5, lamp);
        }
        return t;
    }

    /** How high a support at x reaches: just under the ring's outer edge. */
    private static int supportTop(int x) {
        double dx = x + 0.5;
        return (int) Math.floor(RING_Y - Math.sqrt(Math.max(0, RING_OUTER * RING_OUTER - dx * dx)));
    }
}
