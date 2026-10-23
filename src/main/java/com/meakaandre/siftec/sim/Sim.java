package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.sim.SimData.Link;
import com.meakaandre.siftec.sim.SimData.Node;
import com.meakaandre.siftec.sim.SimData.Recipe;
import com.meakaandre.siftec.sim.SimData.Slot;
import com.meakaandre.siftec.sim.SimData.Vehicle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The background simulation itself: one step of one group of linked buildings, worked out from nothing but the plain
 * data in {@link SimData} (so it can run off the server thread). In one step, from each node's {@code simTime} to
 * {@code now}:
 * <ol>
 * <li>each power grid all of whose parts are simulated is balanced Satisfactory's way, as {@code PowerGrids} does a
 * loaded one: generators give what is used and burn fuel for that much, Power Storage charges and covers shortfalls, and
 * a grid that cannot be covered blows its fuse (a grid with a loaded part is balanced by {@code PowerGrids} itself, and
 * its simulated members' power was set before the step);</li>
 * <li>what has come to the end of a belt or pipe goes into the building there, as far as it has room;</li>
 * <li>each building runs its recipe at its Satisfactory time for as long as it has its inputs, room for its outputs and
 * power: a miner breaks its node at its Mk's speed after the purity's regrowth, the Smelter smelts a batch at a time, an
 * extractor fills its tank, a Sink takes what it is given;</li>
 * <li>each belt or pipe takes from the output at its source, as fast as it carries, and what it took arrives after the
 * time it takes along it;</li>
 * <li>Drone Ports send and receive their drones, and vehicles drive their routes by the time each leg takes, stopping at
 * their Truck Stations to load and unload.</li>
 * </ol>
 * Nothing here touches the world; what has to (a delivery to a building whose chunk ticks, Sink points, a drone landing
 * at a loaded port, a vehicle reaching loaded land) is left in the data for the server thread ({@link Factories}).
 */
public final class Sim {
    /** The water a Coal Generator or Nuclear Power Plant drinks. */
    static final String WATER = "fluid:minecraft:water";
    /** Added before flooring a running fraction, so a sum that should be whole is not lost to rounding. */
    static final double FLOOR_EPSILON = 1e-9;
    /** Most passes of one step's loop (a miner's: {@link #MINE_GUARD}): a stop against a loop that never ends. */
    static final int GUARD = 10_000, MINE_GUARD = 100_000;

    private Sim() {
    }

    /** A group of linked buildings stepped together. */
    public static final class Group {
        public final List<Node> nodes = new ArrayList<>();
        public final List<Link> links = new ArrayList<>();
        public final List<Vehicle> vehicles = new ArrayList<>();
        /** Power grids whose every member is simulated (balanced here), as lists of their simulated members. */
        public final List<List<Node>> grids = new ArrayList<>();
        public final Map<String, Node> byKey = new HashMap<>();
        /** Every node of the dimension, simulated or not (read only: a far Drone Port, a Truck Station). */
        public final Map<String, Node> all;

        public Group(Map<String, Node> all) {
            this.all = all;
        }

        public void add(Node n) {
            nodes.add(n);
            byKey.put(n.key(), n);
        }
    }

    public static void step(Group g, long now) {
        for (List<Node> grid : g.grids) SimPowerBalance.power(grid, now);
        for (Link l : g.links) {
            Node to = g.byKey.get(l.to);
            if (to != null && to.sim) SimBelts.deliver(l, to, now);
        }
        for (Node n : g.nodes) {
            if (!n.sim) continue;
            SimBuildings.advance(n, now, g);
        }
        for (Link l : g.links) {
            Node from = g.byKey.get(l.from);
            if (from != null && from.sim) SimBelts.pull(l, from, now);
        }
        for (Vehicle v : g.vehicles) SimRoutes.drive(v, now, g);
        for (Node n : g.nodes) if (n.sim) n.simTime = Math.max(n.simTime, now);
    }

    // ------------------------------------------------------------------------------------------------ counting

    static long total(Map<String, Long> m) {
        long n = 0;
        for (long v : m.values()) n += v;
        return n;
    }

    static long get(Map<String, Long> m, String k) {
        Long v = m.get(k);
        return v == null ? 0 : v;
    }

    static void add(Map<String, Long> m, String k, long n) {
        if (n == 0) return;
        long v = get(m, k) + n;
        if (v == 0) m.remove(k);
        else m.put(k, v);
    }

    /** How many of an ingredient a node holds at its input: an item key, or "#tag" for any item of the tag. */
    static long have(Node n, String key) {
        if (!key.startsWith("#")) return get(n.in, key);
        long c = 0;
        for (String alt : n.alts.getOrDefault(key, List.of())) c += get(n.in, alt);
        return c;
    }

    /** Takes an ingredient from the input (for a tag, any of its items, in order). */
    static void take(Node n, String key, long count) {
        if (!key.startsWith("#")) {
            add(n.in, key, -count);
            return;
        }
        for (String alt : n.alts.getOrDefault(key, List.of())) {
            if (count <= 0) return;
            long t = Math.min(count, get(n.in, alt));
            add(n.in, alt, -t);
            count -= t;
        }
    }

    /** True if a recipe takes this item at its input (itself, or through a tag). */
    static boolean takes(Node n, Recipe r, String key) {
        if (r.in.containsKey(key)) return true;
        for (String k : r.in.keySet()) if (k.startsWith("#") && n.alts.getOrDefault(k, List.of()).contains(key)) return true;
        return false;
    }

    static void addAll(Map<String, Long> into, Map<String, Long> what, long times) {
        for (Map.Entry<String, Long> e : what.entrySet()) add(into, e.getKey(), e.getValue() * times);
    }

    // ------------------------------------------------------------------------------------------------ slots

    /** Adds to a run of slots what fits (merging with the same key up to its stack size); returns what did not. */
    static long addSlots(List<Slot> slots, int from, int to, String key, long count) {
        int max = Keys.max(key);
        for (int i = from; i < to && count > 0; i++) {
            Slot s = slots.get(i);
            if (!s.empty() && s.key.equals(key) && s.count < max) {
                long n = Math.min(count, max - s.count);
                s.count += n;
                count -= n;
            }
        }
        for (int i = from; i < to && count > 0; i++) {
            Slot s = slots.get(i);
            if (s.empty()) {
                long n = Math.min(count, max);
                s.key = key;
                s.count = n;
                count -= n;
            }
        }
        return count;
    }

    static boolean emptySlots(List<Slot> slots, int from, int to) {
        for (int i = from; i < to && i < slots.size(); i++) if (!slots.get(i).empty()) return false;
        return true;
    }

    static void pad(List<Slot> slots, int size) {
        while (slots.size() < size) slots.add(new Slot());
    }
}
