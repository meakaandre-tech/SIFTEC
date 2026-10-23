package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.sim.SimData.Link;
import com.meakaandre.siftec.sim.SimData.Node;
import com.meakaandre.siftec.sim.SimData.Recipe;
import com.meakaandre.siftec.sim.SimData.Transit;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

/** Simulated belts and pipes: what a node takes in, what reaches a link's end, and what a link takes from its source. */
final class SimBelts {
    /** Ticks a belt's unused allowance is kept for at least (a long step keeps its own length). */
    static final int ALLOWANCE_TICKS = 40;

    private SimBelts() {
    }

    /** How much of a key a node takes in now. */
    static long room(Node n, String key, boolean fluid) {
        switch (n.mode) {
            case STORE -> {
                return fluid ? Math.max(0, n.fluidOutCap - Sim.total(n.fout)) : Math.max(0, n.outCap - Sim.total(n.out));
            }
            case CRAFT -> {
                boolean wanted = false;
                for (Recipe r : n.recipes) if (fluid ? r.fin.containsKey(key) : Sim.takes(n, r, key)) {
                    wanted = true;
                    break;
                }
                if (!wanted) return 0;
                return fluid ? Math.max(0, n.fluidInCap - Sim.total(n.fin)) : Math.max(0, n.inCap - Sim.total(n.in));
            }
            case SMELT -> {
                for (Recipe r : n.recipes) if (r.in.containsKey(key)) {
                    // the Smelter's input slot holds one stack, of one ore at a time
                    for (String k : n.in.keySet()) if (!k.equals(key)) return 0;
                    return Math.max(0, n.inCap - Sim.total(n.in));
                }
                return 0;
            }
            case GEN -> {
                if (fluid && key.equals(Sim.WATER) && n.water > 0) return Math.max(0, n.fluidInCap - Sim.total(n.fin));
                if (!n.fuelMj.containsKey(key)) return 0;
                if (fluid) return Math.max(0, n.fluidInCap - Sim.total(n.fin));
                for (String k : n.in.keySet()) if (!k.equals(key)) return 0;
                return Math.max(0, n.inCap - Sim.total(n.in));
            }
            case SINK -> {
                if (fluid || !n.worth.containsKey(key)) return 0;
                return Math.max(0, n.inCap - Sim.total(n.in));
            }
            default -> {
                return 0;
            }
        }
    }

    static void put(Node n, String key, long count, boolean fluid) {
        Map<String, Long> into = n.mode == NodeMode.STORE ? (fluid ? n.fout : n.out) : (fluid ? n.fin : n.in);
        Sim.add(into, key, count);
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
            long can = Math.min((long) Math.floor(l.allowance + Sim.FLOOR_EPSILON), space);
            if (can <= 0) break;
            long n = Math.min(can, Sim.get(src, key));
            if (n <= 0) continue;
            Sim.add(src, key, -n);
            l.allowance -= n;
            space -= n;
            Transit last = l.transit.isEmpty() ? null : l.transit.getLast();
            long at = now + l.delay;
            if (last != null && last.at == at && last.key.equals(key)) last.n += n;
            else l.transit.add(new Transit(at, key, n));
        }
    }
}
