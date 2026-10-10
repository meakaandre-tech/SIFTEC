package com.meakaandre.siftec.collect;

import java.util.Locale;

/** The things found lying around the world that feed the MAM. */
public enum Collectible {
    BLUE_POWER_SLUG(50, false, "", 7), YELLOW_POWER_SLUG(25, false, "", 8), MERCER_SPHERE(15, false, "object:mercer_sphere", 10),
    PURPLE_POWER_SLUG(7, true, "", 10), SOMERSLOOP(3, true, "object:somersloop", 10),
    /** A cargo pod with wreckage round it. It holds a Hard Drive. */
    CRASH_SITE(12, false, "", 10);

    public final int weight;
    /** Only found well away from world spawn. */
    public final boolean far;
    /** The research the Object Scanner needs before it can look for this one; empty for none. */
    public final String scannerToken;
    /** How much light its block gives off: the rarer slugs glow brighter. */
    public final int light;

    Collectible(int weight, boolean far, String scannerToken, int light) {
        this.weight = weight;
        this.far = far;
        this.scannerToken = scannerToken;
        this.light = light;
    }

    /** One of the three power slugs: a small blob on the ground, with nothing to bump into. */
    public boolean slug() {
        return this == BLUE_POWER_SLUG || this == YELLOW_POWER_SLUG || this == PURPLE_POWER_SLUG;
    }

    /** The item a company gets for collecting one. */
    public String itemId() {
        return this == CRASH_SITE ? "hard_drive" : id();
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
