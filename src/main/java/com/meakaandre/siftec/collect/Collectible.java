package com.meakaandre.siftec.collect;

import java.util.Locale;

/** The things found lying around the world that feed the MAM. */
public enum Collectible {
    BLUE_POWER_SLUG(50, false, ""), YELLOW_POWER_SLUG(25, false, ""), MERCER_SPHERE(15, false, "object:mercer_sphere"),
    PURPLE_POWER_SLUG(7, true, ""), SOMERSLOOP(3, true, "object:somersloop");

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

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
