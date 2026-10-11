package com.meakaandre.siftec.prefab;

import com.meakaandre.siftec.block.Facing;
import com.meakaandre.siftec.registry.ModBlocks;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/**
 * The two structures, at Satisfactory's size with the player 1.8 blocks tall (one metre is one block).
 * <p>
 * HUB: 14 wide, 26 long, 28 high with the FICSIT Freighter docked (the wiki's 14 x 26 x 28 m). From the front: the
 * entrance steps, the terminal hall (HUB Terminal, Craft Bench, Personal Storage Box), the living quarters (bunk
 * beds, lockers, desk, washroom) and the burner deck at the back (two burners), with the freighter's dock tower
 * over the deck. Levels are the HUB upgrades done (0 to 6); level 7 is upgrade 6 with the freighter docked.
 * <p>
 * Wormhole Gateway (the Space Elevator): 48 wide and 54 long like the Space Elevator (48 x 54 m), but 60 high
 * instead of 118, so it stays well inside the build height and in few chunk sections. A raised deck on a frame and
 * pillars, the terminal in a bay under the front of the deck, and the tower in the middle with one ring per phase
 * that lights up when the phase is delivered; Phase 5 adds the rift crown on top. Levels are the phases done.
 */
public final class Templates {
    public static final int HUB_MAX = 7, GATEWAY_MAX = 5;
    /** Where the terminal stands in each structure. */
    public static final int[] HUB_TERMINAL = {0, 1, 5}, GATEWAY_TERMINAL = {0, 1, 7};
    /** Spots of the HUB's parts, for the tests. */
    public static final int[] STORAGE = {3, 1, 4}, BENCH = {-4, 1, 4}, BURNER_1 = {-4, 1, 21}, BURNER_2 = {3, 1, 21}, BED = {-6, 1, 12},
        TOWER = {-1, 10, 22}, FREIGHTER = {0, 22, 20};
    /** One block of each gateway ring (phase 1 to 5), and of the crown. */
    public static final int[][] RINGS = {{-7, 10, 26}, {-7, 19, 26}, {-7, 28, 26}, {-7, 37, 26}, {-7, 46, 26}};
    public static final int[] CROWN = {-6, 58, 21};
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

        // ---- the platform: entrance steps at the front, the floor, an orange edge
        t.box(0, M, -7, 0, 0, 6, 0, 0, step);
        t.box(0, M, -7, 0, 1, 6, 0, 25, floor);
        t.box(0, M, -7, 0, 1, -7, 0, 25, orange);
        t.box(0, M, 6, 0, 1, 6, 0, 25, orange);
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
        // the Craft Bench between two counters
        t.set(0, M, -5, 1, 4, metal);
        t.set(0, M, BENCH[0], BENCH[1], BENCH[2], ModBlocks.CRAFT_BENCH.get().defaultBlockState());
        t.set(0, M, -3, 1, 4, metal);
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

    private static BlockState bed(BedPart part) {
        return ModBlocks.HUB_BED.get().defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, part);
    }

    /** A burner pad at x: empty until the upgrade, then the burner in a housing with a chimney behind; its top stays open for a shaft. */
    private static void burner(Template t, int x, int from) {
        int M = HUB_MAX;
        t.set(0, M, x, 0, 21, s("orange"));
        t.set(0, from - 1, x, 1, 21, Template.AIR);
        t.set(from, M, x, 1, 21, ModBlocks.HUB_BURNER.get().defaultBlockState().setValue(Facing.FACING, Direction.SOUTH));
        t.set(from, M, x, 2, 21, Template.AIR);
        t.set(from, M, x - 1, 1, 21, s("metal"));
        t.set(from, M, x + 1, 1, 21, s("metal"));
        t.set(from, M, x, 1, 22, s("metal"));
        t.box(from, M, x, 1, 23, x, 5, 23, s("metal"));
        t.set(from, M, x, 6, 23, s("orange"));
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
        BlockState white = s("white"), orange = s("orange"), metal = s("railway"), floor = s("floor"), glass = s("glass"),
            lamp = s("lamp"), step = s("step"), coil = s("coil"), lit = s("coil_lit"), rift = s("rift"), air = Template.AIR;
        int x0 = -24, x1 = 23, z0 = 0, z1 = 53;

        // ---- the frame under the deck and its pillars
        t.ring(0, M, x0, 0, z0, x1, 3, z1, metal);
        for (int x = -16; x <= 16; x += 8)
            for (int z = 8; z <= 48; z += 8) {
                if (Math.abs(x) <= 8 && z <= 10) continue;
                t.box(0, M, x, 0, z, x, 3, z, metal);
            }
        // ---- the terminal bay under the front of the deck
        t.box(0, M, -6, 0, z0, 5, 3, z0, air);
        t.box(0, M, -6, 1, 1, 5, 3, 9, air);
        t.box(0, M, -6, 0, 0, 5, 0, 0, step);
        t.box(0, M, -6, 0, 1, 5, 0, 9, floor);
        t.box(0, M, -7, 0, 0, -7, 3, 10, metal);
        t.box(0, M, 6, 0, 0, 6, 3, 10, metal);
        t.box(0, M, -6, 0, 10, 5, 3, 10, metal);
        t.set(0, M, GATEWAY_TERMINAL[0], GATEWAY_TERMINAL[1], GATEWAY_TERMINAL[2],
            ModBlocks.WORMHOLE_GATEWAY.get().defaultBlockState().setValue(Facing.FACING, Direction.SOUTH));
        t.box(0, M, -1, 1, 8, 1, 2, 8, metal);
        t.set(0, M, 0, 2, 8, lamp);
        t.set(0, M, -1, 1, 7, metal);
        t.set(0, M, 1, 1, 7, metal);
        // ---- the deck: white, an orange border, orange cross stripes, lamps along them
        t.box(0, M, x0, 4, z0, x1, 4, z1, white);
        t.ring(0, M, x0, 4, z0, x1, 4, z1, orange);
        t.box(0, M, -1, 4, z0, 0, 4, z1, orange);
        t.box(0, M, x0, 4, 26, x1, 4, 27, orange);
        for (int x = -20; x <= 20; x += 10) t.set(0, M, x, 4, 26, lamp);
        for (int z = 6; z <= 50; z += 11) t.set(0, M, -1, 4, z, lamp);
        // corner pylons with a lamp on top
        for (int[] c : new int[][]{{-22, 2}, {21, 2}, {-22, 51}, {21, 51}}) {
            t.box(0, M, c[0], 5, c[1], c[0], 14, c[1], metal);
            t.set(0, M, c[0], 15, c[1], lamp);
        }
        // ---- the tower: 12 x 12, hollow, white walls, dark corners, glass slits
        int tx0 = -6, tx1 = 5, tz0 = 21, tz1 = 32, top = 56;
        t.ring(0, M, tx0, 5, tz0, tx1, top - 1, tz1, white);
        for (int[] c : new int[][]{{tx0, tz0}, {tx1, tz0}, {tx0, tz1}, {tx1, tz1}}) t.box(0, M, c[0], 5, c[1], c[0], top, c[1], metal);
        t.box(0, M, -1, 6, tz0, 0, top - 2, tz0, glass);
        t.box(0, M, -1, 6, tz1, 0, top - 2, tz1, glass);
        t.box(0, M, tx0, 6, 26, tx0, top - 2, 27, glass);
        t.box(0, M, tx1, 6, 26, tx1, top - 2, 27, glass);
        t.box(0, M, tx0, top, tz0, tx1, top, tz1, metal);
        // ---- one ring per phase around the tower: dark until the phase is delivered, then lit
        for (int phase = 1; phase <= 5; phase++) {
            int y = RINGS[phase - 1][1];
            t.ring(0, phase - 1, tx0 - 1, y, tz0 - 1, tx1 + 1, y + 1, tz1 + 1, coil);
            t.ring(phase, M, tx0 - 1, y, tz0 - 1, tx1 + 1, y + 1, tz1 + 1, lit);
        }
        // ---- Phase 5: the rift crown on top
        for (int[] c : new int[][]{{tx0, tz0}, {tx1, tz0}, {tx0, tz1}, {tx1, tz1}}) t.box(5, M, c[0], top + 1, c[1], c[0], top + 3, c[1], rift);
        t.ring(5, M, tx0, top + 1, tz0, tx1, top + 1, tz1, rift);
        t.ring(5, M, -3, top + 3, 24, 2, top + 3, 29, rift);
        t.box(5, M, -1, top + 1, 26, 0, top + 3, 27, rift);
        return t;
    }
}
