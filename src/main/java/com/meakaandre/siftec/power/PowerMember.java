package com.meakaandre.siftec.power;

/**
 * Something on a power grid, as {@link PowerGrids} balances it each tick. All power is in SU, energy in SU-ticks.
 * A member can be any mix of these; everything defaults to taking no part.
 * <ul>
 * <li>a generator offers {@link #capacity()} and is told what was drawn from it ({@link #generated}), and burns fuel
 * for that much only, as Satisfactory's generators do;</li>
 * <li>a consumer asks for {@link #demand()} and is told whether it runs this tick ({@link #supplied});</li>
 * <li>a store (Power Storage) charges from what is spare and covers what is short;</li>
 * <li>an Alien Power Augmenter adds {@link #boost()} to every boostable generator on its grid;</li>
 * <li>a sink (a New Age block that only takes energy, a street light, an energiser) takes what is left over and never
 * counts toward the fuse.</li>
 * </ul>
 * Generators and stores hold the grid's fuse: when it trips they all remember it, and resetting any of them resets
 * the grid.
 */
public interface PowerMember {
    default double capacity() {
        return 0;
    }

    /** Whether an Augmenter's boost applies to this generator (not to the Augmenters themselves, nor to New Age blocks). */
    default boolean boostable() {
        return false;
    }

    /** What was drawn from this generator this tick, before any boost (so fuel burns for this much). */
    default void generated(double su) {
    }

    default double demand() {
        return 0;
    }

    default void supplied(boolean on) {
    }

    default double stored() {
        return 0;
    }

    default double storeCapacity() {
        return 0;
    }

    /** SU it can take in a tick while charging. */
    default double chargeRate() {
        return 0;
    }

    /** Changes what is stored by this many SU-ticks (negative: given out). */
    default void store(double suTicks) {
    }

    default double boost() {
        return 0;
    }

    default double sink() {
        return 0;
    }

    default void sunk(double su) {
    }

    default boolean holdsFuse() {
        return false;
    }

    default boolean tripped() {
        return false;
    }

    default void trip(boolean tripped) {
    }

    /** The grid as it was balanced this tick, for status lines. */
    default void grid(GridStats stats) {
    }
}
