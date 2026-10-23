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
    /** An Alien Power Augmenter's boost while fed with matrices (+30%), and unfed (+10%), as in Satisfactory. */
    double FED_BOOST = 0.3, UNFED_BOOST = 0.1;

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

    /**
     * True for a generator whose machines have what they need but are still getting going (a boiler heating up, an
     * engine spinning up): a grid short of power while one is starting has no power yet, but its fuse holds.
     */
    default boolean starting() {
        return false;
    }

    /**
     * Ticks a generator may count as starting after it last became ready ({@link #startWindow}): one that never gets going
     * (a Geothermal Generator with no turbine, a HUB burner missing its brushes, an overstressed coil) stops counting, so
     * its grid's fuse can blow again.
     */
    int STARTING_TICKS = 100;

    /**
     * Helper for {@link #starting}: {@code ready} is whether the machine has what it needs and makes nothing yet;
     * {@code since} holds when it last became ready (Long.MIN_VALUE while not). Returns the new {@code since} in [0] and
     * whether it still counts as starting in [1] (1 or 0).
     */
    static long[] startWindow(boolean ready, long since, long now) {
        if (!ready) return new long[]{Long.MIN_VALUE, 0};
        if (since == Long.MIN_VALUE) since = now;
        return new long[]{since, now - since <= STARTING_TICKS ? 1 : 0};
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

    /** True once the block this member stands for is gone (broken, or its chunk unloaded): its grid is worked out again at once. */
    default boolean gone() {
        return false;
    }

    // ---- while it is not loaded (PowerMemory)

    /** SU-ticks of fuel a generator holds, banked and waiting at its input: what it can still give while it is not loaded. */
    default double reserve() {
        return Double.POSITIVE_INFINITY;
    }

    /** SU it gives while it is not loaded, given what it could give this tick (a Geothermal Generator: its geyser's average, not a burst). */
    default double steady(double capacity) {
        return capacity;
    }

    /** Burns this many SU-ticks of its fuel: what it gave while it was not loaded, taken when it loads again. */
    default void spend(double suTicks) {
    }

    /** Ticks an Alien Power Augmenter's fed boost lasts on what it holds (its matrices), while it is not loaded. */
    default int boostTicks() {
        return Integer.MAX_VALUE;
    }

    /** Uses up this many ticks of an Augmenter's fed boost: what it gave while it was not loaded. */
    default void spendBoost(int ticks) {
    }
}
