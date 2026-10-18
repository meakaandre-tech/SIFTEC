package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.sim.SimData.Link;
import com.meakaandre.siftec.sim.SimData.Node;
import com.meakaandre.siftec.sim.SimData.Recipe;
import com.meakaandre.siftec.sim.SimData.Slot;
import com.meakaandre.siftec.sim.SimData.Transit;
import com.meakaandre.siftec.sim.SimData.Vehicle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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
    /** What an idle powered building draws (BuildingPower.STANDBY_SU). */
    static final double STANDBY = 1;
    /** Ticks an Alien Power Matrix keeps an Augmenter fed (BuildingPower.MATRIX_TICKS). */
    static final long MATRIX_TICKS = 240;
    /** mB of sulfuric acid the Geothermal Generator leaves a tick while it gives power. */
    static final double ACID_PER_TICK = 50 / 20.0;
    /** A stopped vehicle stands this long at a Truck Station (VehicleEntity.DOCK_TICKS). */
    static final int DOCK_TICKS = 100;
    static final int PORT_OUT = 0, PORT_FUEL = 9, PORT_IN = 10, PORT_SIZE = 19;
    static final int IDLE = 0, OUTBOUND = 1, HOMEBOUND = 2;
    /** The water a Coal Generator or Nuclear Power Plant drinks. */
    static final String WATER = "fluid:minecraft:water";
    /** Ticks a belt's unused allowance is kept for at least (a long step keeps its own length). */
    static final int ALLOWANCE_TICKS = 40;

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
        for (List<Node> grid : g.grids) power(grid, now);
        for (Link l : g.links) {
            Node to = g.byKey.get(l.to);
            if (to != null && to.sim) deliver(l, to, now);
        }
        for (Node n : g.nodes) {
            if (!n.sim) continue;
            advance(n, now, g);
        }
        for (Link l : g.links) {
            Node from = g.byKey.get(l.from);
            if (from != null && from.sim) pull(l, from, now);
        }
        for (Vehicle v : g.vehicles) drive(v, now, g);
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

    // ------------------------------------------------------------------------------------------------ power

    /** What a consumer asks for over the step: its SU while it has work, its standby draw while it has none. */
    static double demand(Node n, long now) {
        if (n.su <= 0) return 0;
        return working(n, now) ? n.su : STANDBY;
    }

    /** Whether a building has work right now (BuildingLogic.working, as far as the simulation can tell). */
    static boolean working(Node n, long now) {
        return switch (n.mode) {
            case "craft" -> n.busyUntil > now || startable(n) != null;
            case "smelt" -> !n.pending.isEmpty() || smeltable(n) != null;
            case "mine" -> total(n.out) + Math.ceil(n.minePer) <= n.outCap;
            case "extract" -> total(n.fout) < n.fluidOutCap;
            case "sink" -> !n.in.isEmpty();
            default -> true;
        };
    }

    static boolean generator(Node n) {
        return switch (n.power) {
            case "biomass", "coal", "fuel", "nuclear", "geothermal", "augmenter" -> true;
            default -> false;
        };
    }

    static boolean holdsFuse(Node n) {
        return generator(n) || n.power.equals("storage");
    }

    /** Energy (SU-ticks) a generator could still give: what its machines hold and what its fuel at the input holds. */
    static double fuelEnergy(Node n) {
        if (n.power.equals("geothermal") || n.power.equals("augmenter")) return Double.MAX_VALUE;
        double e = Math.max(0, n.energy);
        for (Map.Entry<String, Long> f : n.in.entrySet()) {
            Double mj = n.fuelMj.get(f.getKey());
            // a fuel rod counts only while its waste has room to go
            if (mj != null) e += suTicksOfMj(mj) * Math.min(f.getValue(), wasteRoom(n, f.getKey()));
        }
        for (Map.Entry<String, Long> f : n.fin.entrySet()) {
            Double mj = n.fuelMj.get(f.getKey());
            if (mj != null) e += suTicksOfMj(mj) * f.getValue();
        }
        return e;
    }

    /** How many of this fuel the output still has room for the waste of (BuildingPower.wasteFits); any number if it leaves none. */
    static long wasteRoom(Node n, String fuel) {
        String waste = n.waste.get(fuel);
        if (waste == null) return Long.MAX_VALUE;
        for (String k : n.out.keySet()) if (!k.equals(waste)) return 0;
        long each = Math.max(1, n.wasteCount.getOrDefault(fuel, 1));
        return Math.max(0, n.outCap - total(n.out)) / each;
    }

    /** What a generator can give now: its full SU while it has fuel (and water, if it drinks), else nothing. */
    static double capacity(Node n) {
        if (n.tripped || n.fullSu <= 0) return 0;
        if (n.water > 0 && get(n.fin, WATER) <= 0) return 0;
        return fuelEnergy(n) > 0 ? n.fullSu : 0;
    }

    /** A generator drinks its water for this many ticks at full power. */
    static void drink(Node n, double ticksAtFull) {
        if (n.water <= 0 || ticksAtFull <= 0) return;
        n.waterFrac += n.water * ticksAtFull;
        long mb = Math.min((long) n.waterFrac, get(n.fin, WATER));
        add(n.fin, WATER, -mb);
        n.waterFrac -= mb;
        // no more water: it stops owing what it could not drink
        if (get(n.fin, WATER) <= 0) n.waterFrac = Math.min(n.waterFrac, 1);
    }

    static double suTicksOfMj(double mj) {
        return mj * 204.8 * 20;
    }

    /** Burns this much of a generator's energy: out of its machines first, then its next fuel. */
    static void burn(Node n, double suTicks) {
        if (suTicks <= 0) return;
        n.given += suTicks;
        if (n.power.equals("geothermal") || n.power.equals("augmenter")) return;
        n.energy -= suTicks;
        while (n.energy < 0) {
            String fuel = null;
            boolean fluid = false;
            for (String k : n.in.keySet()) if (n.fuelMj.containsKey(k) && wasteRoom(n, k) > 0) {
                fuel = k;
                break;
            }
            if (fuel == null) for (String k : n.fin.keySet()) if (n.fuelMj.containsKey(k)) {
                fuel = k;
                fluid = true;
                break;
            }
            if (fuel == null) {
                n.energy = 0;
                return;
            }
            double per = suTicksOfMj(n.fuelMj.get(fuel));
            if (per <= 0) {
                n.energy = 0;
                return;
            }
            n.lastFuel = fuel;
            if (fluid) {
                long have = get(n.fin, fuel);
                long mb = Math.min(have, (long) Math.ceil(-n.energy / per));
                add(n.fin, fuel, -mb);
                n.energy += mb * per;
                if (mb <= 0) {
                    n.energy = 0;
                    return;
                }
            } else {
                add(n.in, fuel, -1);
                n.energy += per;
                // a spent fuel rod leaves its waste
                String waste = n.waste.get(fuel);
                if (waste != null) add(n.out, waste, n.wasteCount.getOrDefault(fuel, 1));
            }
        }
    }

    /** Balances a power grid whose members are all simulated, for the step that ends now. */
    static void power(List<Node> grid, long now) {
        long dt = 0;
        for (Node n : grid) dt = Math.max(dt, now - n.simTime);
        if (dt <= 0) return;
        double boost = 0, boosted = 0, flat = 0, demand = 0, stored = 0, chargeWant = 0;
        boolean tripped = false, fuse = false;
        double[] caps = new double[grid.size()];
        for (int i = 0; i < grid.size(); i++) {
            Node n = grid.get(i);
            if (holdsFuse(n)) {
                fuse = true;
                tripped |= n.tripped;
            }
            if (n.power.equals("augmenter")) {
                if (!n.tripped) {
                    caps[i] = n.fullSu;
                    flat += n.fullSu;
                    boost += n.fed > 0 || hasMatrix(n) ? 0.3 : 0.1;
                }
            } else if (generator(n)) {
                if (!n.tripped) {
                    caps[i] = Math.min(n.fullSu, fuelEnergy(n) / dt);
                    // a generator that drinks gives only as long as its water lasts
                    if (n.water > 0) caps[i] = Math.min(caps[i], n.fullSu * get(n.fin, WATER) / (n.water * dt));
                    boosted += caps[i];
                }
            } else if (n.power.equals("storage")) {
                stored += n.stored;
                if (!n.tripped) chargeWant += Math.max(0, Math.min(storageRate(), storageCap() - n.stored) * dt);
            } else {
                n.demandNow = demand(n, now);
                demand += n.demandNow;
            }
        }
        double own = boosted * (1 + boost) + flat;
        boolean on;
        double load;
        if (tripped) {
            on = false;
            load = 0;
        } else if (demand <= own) {
            on = true;
            double spare = (own - demand) * dt;
            double charge = Math.min(spare, chargeWant);
            if (charge > 0) for (Node n : grid) {
                if (!n.power.equals("storage") || n.tripped) continue;
                double w = Math.max(0, Math.min(storageRate(), storageCap() - n.stored) * dt);
                n.stored = Math.min(storageCap(), n.stored + charge * w / chargeWant);
            }
            load = own <= 0 ? 0 : Math.min(1, (demand * dt + charge) / (own * dt));
        } else {
            double shortBy = (demand - own) * dt;
            if (stored > 0 && stored >= shortBy) {
                on = true;
                for (Node n : grid) if (n.power.equals("storage") && n.stored > 0) n.stored = Math.max(0, n.stored - shortBy * n.stored / stored);
                load = 1;
            } else if (fuse) {
                on = false;
                load = 0;
                for (Node n : grid) if (holdsFuse(n)) n.tripped = true;
            } else {
                on = false;
                load = 0;
            }
        }
        for (int i = 0; i < grid.size(); i++) {
            Node n = grid.get(i);
            if (generator(n)) {
                // the reactor burns at full whatever the load, as Satisfactory 1.0's does
                double give = n.power.equals("nuclear") && !n.tripped ? caps[i] : caps[i] * load;
                n.capacityNow = caps[i];
                if (n.power.equals("augmenter")) feedAugmenter(n, dt, on);
                else {
                    burn(n, give * dt);
                    if (n.fullSu > 0) drink(n, give * dt / n.fullSu);
                }
                if (n.power.equals("geothermal") && give > 0 && !n.fluidOut.isEmpty()) {
                    n.frac += ACID_PER_TICK * dt;
                    long mb = (long) n.frac;
                    long room = Math.max(0, n.fluidOutCap - total(n.fout));
                    add(n.fout, n.fluidOut, Math.min(mb, room));
                    n.frac -= mb;
                }
            } else if (!n.power.equals("storage")) {
                n.powered = on || n.su <= 0;
            }
        }
    }

    static double storageCap() {
        return 100 * 204.8 * 72_000;
    }

    static double storageRate() {
        return 100 * 204.8;
    }

    private static boolean hasMatrix(Node n) {
        for (String k : n.in.keySet()) if (n.fuelMj.containsKey(k)) return true;
        return false;
    }

    /** An Augmenter uses up its fed time while it runs, taking its next matrix when the last is spent. */
    private static void feedAugmenter(Node n, long dt, boolean on) {
        if (!on) return;
        long left = dt;
        while (left > 0) {
            if (n.fed <= 0) {
                String matrix = null;
                for (String k : n.in.keySet()) if (n.fuelMj.containsKey(k)) {
                    matrix = k;
                    break;
                }
                if (matrix == null) return;
                add(n.in, matrix, -1);
                n.fed = MATRIX_TICKS;
                n.made++;
            }
            long use = Math.min(left, n.fed);
            n.fed -= use;
            left -= use;
        }
    }

    // ------------------------------------------------------------------------------------------------ buildings

    static void advance(Node n, long now, Group g) {
        long t0 = n.simTime;
        if (now <= t0) return;
        switch (n.mode) {
            case "craft" -> craft(n, t0, now);
            case "smelt" -> smelt(n, t0, now);
            case "mine" -> mine(n, t0, now);
            case "extract" -> {
                if (n.su <= 0 || n.powered) {
                    n.frac += n.fluidRate * (now - t0);
                    long mb = (long) n.frac;
                    long room = Math.max(0, n.fluidOutCap - total(n.fout));
                    long put = Math.min(mb, room);
                    add(n.fout, n.fluidOut, put);
                    n.frac -= mb;
                    if (put < mb) n.frac = 0;
                }
            }
            case "sink" -> {
                if (n.su <= 0 || n.powered) {
                    for (Map.Entry<String, Long> e : n.in.entrySet()) {
                        add(n.sunk, e.getKey(), e.getValue());
                        n.made += e.getValue();
                    }
                    n.in.clear();
                }
            }
            case "port" -> port(n, t0, now, g);
            default -> {
            }
        }
    }

    /** The first recipe whose inputs are there and whose outputs have room, or null. */
    static Recipe startable(Node n) {
        long outNow = total(n.out) + total(n.pending), fluidNow = total(n.fout) + total(n.pendingFluid);
        for (Recipe r : n.recipes) {
            boolean ok = true;
            for (Map.Entry<String, Long> e : r.in.entrySet()) if (have(n, e.getKey()) < e.getValue()) {
                ok = false;
                break;
            }
            if (!ok) continue;
            for (Map.Entry<String, Long> e : r.fin.entrySet()) if (get(n.fin, e.getKey()) < e.getValue()) {
                ok = false;
                break;
            }
            if (!ok) continue;
            if (outNow + total(r.out) > n.outCap) continue;
            if (!r.fout.isEmpty() && fluidNow + total(r.fout) > n.fluidOutCap) continue;
            return r;
        }
        return null;
    }

    private static void finishPending(Node n) {
        addAll(n.out, n.pending, 1);
        addAll(n.fout, n.pendingFluid, 1);
        n.pending.clear();
        n.pendingFluid.clear();
        n.pendingAt = Long.MIN_VALUE;
    }

    /** A Basin building (and the line, packaging and processing buildings): one recipe at a time at its Satisfactory time. */
    static void craft(Node n, long t0, long end) {
        long t = t0;
        for (int guard = 0; guard < 10_000; guard++) {
            if (!n.pending.isEmpty() || !n.pendingFluid.isEmpty()) {
                if (n.pendingAt > end) break;
                t = Math.max(t, n.pendingAt);
                finishPending(n);
            }
            if (n.busyUntil > t) {
                if (n.busyUntil > end) break;
                t = n.busyUntil;
            }
            if (n.su > 0 && !n.powered) break;
            Recipe r = startable(n);
            if (r == null) break;
            for (Map.Entry<String, Long> e : r.in.entrySet()) take(n, e.getKey(), e.getValue());
            for (Map.Entry<String, Long> e : r.fin.entrySet()) add(n.fin, e.getKey(), -e.getValue());
            n.busyUntil = t + Math.max(1, r.ticks);
            n.pendingAt = t + Math.max(0, Math.min(r.outDelay, r.ticks));
            n.pending.putAll(r.out);
            n.pendingFluid.putAll(r.fout);
            n.made++;
            if (n.pendingAt <= t) finishPending(n);
        }
    }

    /** The Smelter's recipe for what is at its input, or null. */
    static Recipe smeltable(Node n) {
        for (Recipe r : n.recipes) {
            String in = r.in.keySet().stream().findFirst().orElse(null);
            if (in != null && get(n.in, in) > 0) return r;
        }
        return null;
    }

    /** The Smelter: up to a batch of one ore at a time, each item taking the recipe's time; the ingots out when the batch is done. */
    static void smelt(Node n, long t0, long end) {
        long t = t0;
        for (int guard = 0; guard < 10_000; guard++) {
            if (!n.pending.isEmpty()) {
                if (n.pendingAt > end) break;
                t = Math.max(t, n.pendingAt);
                finishPending(n);
            }
            if (n.su > 0 && !n.powered) break;
            Recipe r = smeltable(n);
            if (r == null) break;
            String in = r.in.keySet().iterator().next();
            long per = Math.max(1, r.in.get(in));
            long batch = Math.min(r.batch, get(n.in, in) / per);
            if (batch <= 0) break;
            long room = n.outCap - total(n.out);
            long each = Math.max(1, total(r.out));
            batch = Math.min(batch, room / each);
            if (batch <= 0) break;
            add(n.in, in, -batch * per);
            addAll(n.pending, r.out, batch);
            n.pendingAt = n.busyUntil = t + batch * r.ticks + r.batchExtra;
            n.made += batch;
        }
    }

    /** A miner: a break every period (regrowth, then its Mk's break time) while it has room and power. */
    static void mine(Node n, long t0, long end) {
        if (n.mineItem.isEmpty() || n.minePeriod <= 0) return;
        if (n.nextBreak == Long.MIN_VALUE) n.nextBreak = t0 + n.minePeriod;
        for (int guard = 0; guard < 100_000 && n.nextBreak <= end; guard++) {
            if (n.su > 0 && !n.powered) {
                n.nextBreak = end + 1;
                return;
            }
            if (total(n.out) + (long) Math.ceil(n.minePer) > n.outCap) {
                // full: it waits, and breaks as soon as there is room again
                n.nextBreak = end;
                return;
            }
            n.frac += n.minePer;
            long got = (long) Math.floor(n.frac + 1e-9);
            n.frac -= got;
            add(n.out, n.mineItem, got);
            n.mined += got;
            n.made++;
            n.nextBreak += n.minePeriod;
        }
    }

    // ------------------------------------------------------------------------------------------------ belts and pipes

    /** How much of a key a node takes in now. */
    static long room(Node n, String key, boolean fluid) {
        switch (n.mode) {
            case "store" -> {
                return fluid ? Math.max(0, n.fluidOutCap - total(n.fout)) : Math.max(0, n.outCap - total(n.out));
            }
            case "craft" -> {
                boolean wanted = false;
                for (Recipe r : n.recipes) if (fluid ? r.fin.containsKey(key) : takes(n, r, key)) {
                    wanted = true;
                    break;
                }
                if (!wanted) return 0;
                return fluid ? Math.max(0, n.fluidInCap - total(n.fin)) : Math.max(0, n.inCap - total(n.in));
            }
            case "smelt" -> {
                for (Recipe r : n.recipes) if (r.in.containsKey(key)) {
                    // the Smelter's input slot holds one stack, of one ore at a time
                    for (String k : n.in.keySet()) if (!k.equals(key)) return 0;
                    return Math.max(0, n.inCap - total(n.in));
                }
                return 0;
            }
            case "gen" -> {
                if (fluid && key.equals(WATER) && n.water > 0) return Math.max(0, n.fluidInCap - total(n.fin));
                if (!n.fuelMj.containsKey(key)) return 0;
                if (fluid) return Math.max(0, n.fluidInCap - total(n.fin));
                for (String k : n.in.keySet()) if (!k.equals(key)) return 0;
                return Math.max(0, n.inCap - total(n.in));
            }
            case "sink" -> {
                if (fluid || !n.worth.containsKey(key)) return 0;
                return Math.max(0, n.inCap - total(n.in));
            }
            default -> {
                return 0;
            }
        }
    }

    static void put(Node n, String key, long count, boolean fluid) {
        Map<String, Long> into = n.mode.equals("store") ? (fluid ? n.fout : n.out) : (fluid ? n.fin : n.in);
        add(into, key, count);
    }

    /** What has come to the end of a link goes into the node there, in order, as far as it has room. */
    static void deliver(Link l, Node to, long now) {
        Iterator<Transit> it = l.transit.iterator();
        while (it.hasNext()) {
            Transit t = it.next();
            if (t.at > now) break;
            long take = Math.min(t.n, room(to, t.key, l.fluid));
            if (take > 0) put(to, t.key, take, l.fluid);
            t.n -= take;
            if (t.n > 0) break;
            it.remove();
        }
    }

    /** What a link carries along to a node: the next in its order that the node wants (for the boundary's deliveries). */
    static Map<String, Long> source(Node n, boolean fluid) {
        if (fluid) return n.fout;
        return n.out;
    }

    /**
     * A link's allowance after {@code dt} more ticks: what it carries in that time, plus what it did not use before, kept
     * for at most {@link #ALLOWANCE_TICKS} or the step's own length, whichever is longer.
     */
    static double allow(Link l, long dt) {
        double per = l.rate * l.share;
        return Math.min(l.allowance + per * dt, Math.max(1, per * Math.max(dt, ALLOWANCE_TICKS)));
    }

    /** A link takes from its source's output as fast as it carries, up to what fits on it. */
    static void pull(Link l, Node from, long now) {
        long dt = l.last == Long.MIN_VALUE ? 0 : Math.max(0, now - l.last);
        l.last = now;
        l.allowance = allow(l, dt);
        long space = Math.max(0, l.cap - l.carrying());
        Map<String, Long> src = source(from, l.fluid);
        if (src.isEmpty()) return;
        for (String key : new ArrayList<>(src.keySet())) {
            long can = Math.min((long) Math.floor(l.allowance + 1e-9), space);
            if (can <= 0) break;
            long n = Math.min(can, get(src, key));
            if (n <= 0) continue;
            add(src, key, -n);
            l.allowance -= n;
            space -= n;
            Transit last = l.transit.isEmpty() ? null : l.transit.getLast();
            long at = now + l.delay;
            if (last != null && last.at == at && last.key.equals(key)) last.n += n;
            else l.transit.add(new Transit(at, key, n));
        }
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

    // ------------------------------------------------------------------------------------------------ drones

    /**
     * A Drone Port, as its block entity does it: every second at home it sends the drone off when packages wait and there
     * are fire charges for the trip; the trip takes its time each way; at the far port it unloads what fits (into that
     * port's simulation, or for the server thread to hand over at a loaded one) and flies home.
     */
    static void port(Node n, long t0, long end, Group g) {
        pad(n.slots, PORT_SIZE);
        long t = t0;
        for (int guard = 0; guard < 10_000 && t < end; guard++) {
            if (n.state == IDLE) {
                if (!n.hasDrone || n.destination == null) return;
                long next = (t / 20 + 1) * 20;
                if (next > end) return;
                t = next;
                if (emptySlots(n.slots, PORT_OUT, PORT_FUEL)) continue;
                Slot fuel = n.slots.get(PORT_FUEL);
                if (fuel.empty() || fuel.count < n.charges) continue;
                fuel.count -= n.charges;
                if (fuel.count <= 0) n.slots.set(PORT_FUEL, new Slot());
                for (int i = PORT_OUT; i < PORT_FUEL; i++) {
                    Slot s = n.slots.get(i);
                    if (!s.empty()) n.cargo.add(new Slot(s.key, s.count));
                    n.slots.set(i, new Slot());
                }
                n.total = n.ticks = n.flight;
                n.state = OUTBOUND;
                continue;
            }
            if (n.ticks > 0) {
                long use = Math.min(n.ticks, end - t);
                n.ticks -= (int) use;
                t += use;
                if (n.ticks > 0) return;
            }
            if (n.state == HOMEBOUND) {
                List<Slot> left = new ArrayList<>();
                for (Slot s : n.cargo) {
                    long rest = addSlots(n.slots, PORT_IN, PORT_SIZE, s.key, s.count);
                    if (rest > 0) left.add(new Slot(s.key, rest));
                }
                n.cargo = left;
                n.state = IDLE;
                continue;
            }
            // at the far port: once a second, unload what fits
            long next = (t + 19) / 20 * 20;
            if (next > end) return;
            t = Math.max(t, next);
            Node far = n.destination == null ? null : g.all.get(SimData.key(n.dim, n.destination));
            if (far == null || !far.kind.equals("drone_port")) {
                // no port there: nothing to unload into; it comes home with the packages
                n.state = HOMEBOUND;
                n.ticks = n.total;
                continue;
            }
            if (!far.sim) {
                // a port whose chunk ticks: the server thread hands the packages over
                n.handOver = true;
                return;
            }
            pad(far.slots, PORT_SIZE);
            List<Slot> left = new ArrayList<>();
            for (Slot s : n.cargo) {
                long rest = addSlots(far.slots, PORT_IN, PORT_SIZE, s.key, s.count);
                if (rest > 0) left.add(new Slot(s.key, rest));
            }
            n.cargo = left;
            if (!left.isEmpty()) {
                t += 20;
                continue;
            }
            n.state = HOMEBOUND;
            n.ticks = n.total;
        }
    }

    // ------------------------------------------------------------------------------------------------ vehicles

    /** Ticks a leg (to route point {@code to}) takes: as it last drove it for real, or worked out from its length. */
    static double legTicks(Vehicle v, int to) {
        int n = v.points();
        if (n < 2) return 1;
        if (to < v.legTicks.size() && v.legTicks.get(to) > 0) return v.legTicks.get(to);
        int from = (to - 1 + n) % n;
        double dx = v.px(to) - v.px(from), dz = v.pz(to) - v.pz(from);
        double dist = Math.sqrt(dx * dx + dz * dz);
        // the autopilot keeps to 80% of its top speed on the straight, less near a stop
        return Math.max(1, dist / Math.max(0.05, v.speed * 0.8)) + 2;
    }

    static int stopIndex(Vehicle v, int point) {
        for (int i = 0; i < v.stopAt.size(); i++) if (v.stopAt.get(i) == point) return i;
        return -1;
    }

    /** A vehicle drives its route by time: each leg takes its ticks and its fuel; at each stop it loads or unloads. */
    static void drive(Vehicle v, long now, Group g) {
        long dt = now - v.simTime;
        v.simTime = now;
        int n = v.points();
        if (dt <= 0 || n < 2) return;
        double perTick = v.mw / 20.0;
        double left = dt;
        for (int guard = 0; guard < 10_000 && left > 0; guard++) {
            if (v.target >= n) v.target = 0;
            if (v.docked >= 0) {
                double use = Math.min(left, DOCK_TICKS - v.docked);
                v.docked += (int) Math.ceil(use);
                left -= use;
                if (v.docked >= DOCK_TICKS) {
                    v.docked = -1;
                    v.target = (v.target + 1) % n;
                    v.leg = 0;
                }
                continue;
            }
            double total = legTicks(v, v.target);
            if (v.fuel <= 0) {
                v.dry = true;
                break;
            }
            v.dry = false;
            double use = Math.min(left, Math.min(total - v.leg, v.fuel / perTick));
            v.leg += use;
            left -= use;
            v.fuel = Math.max(0, v.fuel - use * perTick);
            if (v.leg + 1e-6 < total) continue;
            int stop = stopIndex(v, v.target);
            if (stop >= 0) {
                dock(v, stop, g);
                v.docked = 0;
                v.leg = 0;
            } else {
                v.target = (v.target + 1) % n;
                v.leg = 0;
            }
        }
        place(v);
    }

    /** Where the vehicle is now, along its leg: for the map, and for coming back into loaded land. */
    static void place(Vehicle v) {
        int n = v.points();
        if (n < 2) return;
        int to = v.target % n, from = (to - 1 + n) % n;
        double f = v.docked >= 0 ? 1 : Math.min(1, v.leg / legTicks(v, to));
        v.x = v.px(from) + (v.px(to) - v.px(from)) * f;
        v.y = v.py(from) + (v.py(to) - v.py(from)) * f;
        v.z = v.pz(from) + (v.pz(to) - v.pz(from)) * f;
        double dx = v.px(to) - v.px(from), dz = v.pz(to) - v.pz(from);
        if (dx * dx + dz * dz > 1e-6) v.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /**
     * Where a vehicle comes back into the world: at a route point it has really reached (its stop, if it stands at one,
     * else the last point it passed), never part way along a leg, which may cut through a hill or over a valley.
     */
    static int returnPoint(Vehicle v) {
        int n = v.points();
        if (n < 1) return 0;
        int to = Math.floorMod(v.target, n);
        return v.docked >= 0 ? to : Math.floorMod(to - 1, n);
    }

    /** At a stop: the Truck Station unloads the vehicle into its vaults or loads it from them, and fills its tank. */
    static void dock(Vehicle v, int stop, Group g) {
        v.stopsMade++;
        long station = v.stopStation.get(stop);
        v.lastStation = station;
        Node s = g.all.get(SimData.key(v.dim, station));
        if (s == null) return;
        if (!s.sim) {
            // a station whose chunk ticks: the server thread loads or unloads the vehicle there, after the step
            v.dockPending = stop;
            return;
        }
        long moved = 0;
        if (s.unload) {
            List<Slot> keep = new ArrayList<>();
            for (Slot c : v.cargo) {
                if (c.empty()) continue;
                long take = Math.min(c.count, Math.max(0, s.outCap - total(s.out)));
                add(s.out, c.key, take);
                moved += take;
                if (c.count - take > 0) keep.add(new Slot(c.key, c.count - take));
            }
            v.cargo = keep;
        } else {
            List<Slot> cargo = new ArrayList<>(v.cargo);
            while (cargo.size() < v.slots) cargo.add(new Slot());
            for (String key : new ArrayList<>(s.out.keySet())) {
                long have = get(s.out, key);
                long rest = addSlots(cargo, 0, v.slots, key, have);
                add(s.out, key, -(have - rest));
                moved += have - rest;
            }
            cargo.removeIf(Slot::empty);
            v.cargo = cargo;
        }
        // the station's fuel tank fills the vehicle's
        for (String key : new ArrayList<>(s.fout.keySet())) {
            Double mj = s.fuelMj.get(key);
            if (mj == null || mj <= 0) continue;
            long room = (long) Math.floor((v.tank - v.fuel) / mj);
            long took = Math.min(room, get(s.fout, key));
            add(s.fout, key, -took);
            v.fuel += took * mj;
        }
        v.itemsMoved += moved;
    }
}
