package com.meakaandre.siftec.collect;

import java.util.Locale;

/**
 * The things found lying around the world that feed the MAM (Exploration and Equipment tab). Each is placed from the
 * world seed, so the Object Scanner finds it in land nobody has visited, and each company can collect each one once.
 */
public enum Collectible {
    /** Common: floating over a small patch of siftslate. Builds Dimensional Depots and their research. */
    MERCER_SPHERE(60, false, "object:mercer_sphere", 10, "alien"),
    /** Rare: in a pocket deep underground (and in Ancient City portal frames and some crash sites). Builds the Alien Power Augmenter. */
    SOMERSLOOP(6, true, "object:somersloop", 10, "alien"),
    /** A cargo pod thrown out of a failed rift, with wreckage round it. It holds a Hard Drive. */
    CRASH_SITE(24, false, "", 6, "hard_drives");

    public final int weight;
    /** Only found well away from world spawn. */
    public final boolean far;
    /** The research the Object Scanner needs before it can look for this one; empty for none. */
    public final String scannerToken;
    /** How much light its block gives off. */
    public final int light;
    /** The MAM tree collecting one starts for the company ("hard_drives": the Hard Drive page). */
    public final String tree;

    Collectible(int weight, boolean far, String scannerToken, int light, String tree) {
        this.weight = weight;
        this.far = far;
        this.scannerToken = scannerToken;
        this.light = light;
        this.tree = tree;
    }

    /** The item a company gets for collecting one. */
    public String itemId() {
        return this == CRASH_SITE ? "hard_drive" : id();
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
