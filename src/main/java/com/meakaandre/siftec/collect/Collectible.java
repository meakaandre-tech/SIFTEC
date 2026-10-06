package com.meakaandre.siftec.collect;

import java.util.Locale;

/** The things found lying around the world that feed the MAM. */
public enum Collectible {
    BLUE_POWER_SLUG(50, false, ""), YELLOW_POWER_SLUG(25, false, ""), MERCER_SPHERE(15, false, "object:mercer_sphere"),
    PURPLE_POWER_SLUG(7, true, ""), SOMERSLOOP(3, true, "object:somersloop"),
    /** A cargo pod with wreckage round it. It holds a Hard Drive. */
    CRASH_SITE(12, false, "");

    public final int weight;
    /** Only found well away from world spawn. */
    public final boolean far;
    /** The research the Object Scanner needs before it can look for this one; empty for none. */
    public final String scannerToken;

    Collectible(int weight, boolean far, String scannerToken) {
        this.weight = weight;
        this.far = far;
        this.scannerToken = scannerToken;
    }

    /** The item a company gets for collecting one. */
    public String itemId() {
        return this == CRASH_SITE ? "hard_drive" : id();
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
