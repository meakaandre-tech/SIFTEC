package com.meakaandre.siftec.belt;

import com.meakaandre.siftec.tweak.SelfMovingBelts;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;

/**
 * Which belt Mk a belt runs as, from its self-moving speed (the milestone speed), and the material of its side trim:
 * andesite for Mk.1 and Mk.2, copper for Mk.3 and Mk.4, brass for Mk.5 and steel for Mk.6. Read on the client from the
 * speed the server sends with the belt, so the trim follows the milestones.
 */
public final class BeltLook {
    /** The belt tiers' items a minute, Mk.1 to Mk.6 (the milestones' "belt:" tokens, Satisfactory's belt speeds). */
    public static final int[] ITEMS_PER_MINUTE = {60, 120, 270, 480, 780, 1200};

    /** The side trim's material. */
    public enum Trim {
        ANDESITE, COPPER, BRASS, STEEL
    }

    private BeltLook() {
    }

    /** The Mk (1 to 6) of a belt carrying this many items a minute; 0 for one that does not move. */
    public static int mk(int itemsPerMinute) {
        if (itemsPerMinute <= 0) return 0;
        for (int i = 0; i < ITEMS_PER_MINUTE.length; i++) if (itemsPerMinute <= ITEMS_PER_MINUTE[i]) return i + 1;
        return ITEMS_PER_MINUTE.length;
    }

    /** A belt's Mk, from the speed it runs at (its controller's self-moving speed). */
    public static int mk(BeltBlockEntity belt) {
        return mk(Math.round(Math.abs(SelfMovingBelts.speed(belt)) * SelfMovingBelts.ITEMS_PER_MINUTE_PER_RPM));
    }

    /** The trim for a Mk (a belt that does not move yet shows Mk.1's). */
    public static Trim trim(int mk) {
        if (mk <= 2) return Trim.ANDESITE;
        if (mk <= 4) return Trim.COPPER;
        if (mk == 5) return Trim.BRASS;
        return Trim.STEEL;
    }
}
