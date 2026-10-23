package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.sim.SimData.Node;
import com.meakaandre.siftec.sim.SimData.Recipe;

import java.util.Map;

/** What a simulated building makes in a step: crafting, smelting, mining, extracting and sinking. */
final class SimBuildings {
    private SimBuildings() {
    }

    static void advance(Node n, long now, Sim.Group g) {
        long t0 = n.simTime;
        if (now <= t0) return;
        switch (n.mode) {
            case CRAFT -> craft(n, t0, now);
            case SMELT -> smelt(n, t0, now);
            case MINE -> mine(n, t0, now);
            case EXTRACT -> {
                if (n.su <= 0 || n.powered) {
                    n.frac += n.fluidRate * (now - t0);
                    long mb = (long) n.frac;
                    long room = Math.max(0, n.fluidOutCap - Sim.total(n.fout));
                    long put = Math.min(mb, room);
                    Sim.add(n.fout, n.fluidOut, put);
                    n.frac -= mb;
                    if (put < mb) n.frac = 0;
                }
            }
            case SINK -> {
                if (n.su <= 0 || n.powered) {
                    for (Map.Entry<String, Long> e : n.in.entrySet()) {
                        Sim.add(n.sunk, e.getKey(), e.getValue());
                        n.made += e.getValue();
                    }
                    n.in.clear();
                }
            }
            case PORT -> SimDrones.port(n, t0, now, g);
            default -> {
            }
        }
    }

    /** The first recipe whose inputs are there and whose outputs have room, or null. */
    static Recipe startable(Node n) {
        long outNow = Sim.total(n.out) + Sim.total(n.pending), fluidNow = Sim.total(n.fout) + Sim.total(n.pendingFluid);
        for (Recipe r : n.recipes) {
            boolean ok = true;
            for (Map.Entry<String, Long> e : r.in.entrySet()) if (Sim.have(n, e.getKey()) < e.getValue()) {
                ok = false;
                break;
            }
            if (!ok) continue;
            for (Map.Entry<String, Long> e : r.fin.entrySet()) if (Sim.get(n.fin, e.getKey()) < e.getValue()) {
                ok = false;
                break;
            }
            if (!ok) continue;
            if (outNow + Sim.total(r.out) > n.outCap) continue;
            if (!r.fout.isEmpty() && fluidNow + Sim.total(r.fout) > n.fluidOutCap) continue;
            return r;
        }
        return null;
    }

    private static void finishPending(Node n) {
        Sim.addAll(n.out, n.pending, 1);
        Sim.addAll(n.fout, n.pendingFluid, 1);
        n.pending.clear();
        n.pendingFluid.clear();
        n.pendingAt = Long.MIN_VALUE;
    }

    /** A Basin building (and the line, packaging and processing buildings): one recipe at a time at its Satisfactory time. */
    static void craft(Node n, long t0, long end) {
        long t = t0;
        for (int guard = 0; guard < Sim.GUARD; guard++) {
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
            for (Map.Entry<String, Long> e : r.in.entrySet()) Sim.take(n, e.getKey(), e.getValue());
            for (Map.Entry<String, Long> e : r.fin.entrySet()) Sim.add(n.fin, e.getKey(), -e.getValue());
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
            if (in != null && Sim.get(n.in, in) > 0) return r;
        }
        return null;
    }

    /** The Smelter: up to a batch of one ore at a time, each item taking the recipe's time; the ingots out when the batch is done. */
    static void smelt(Node n, long t0, long end) {
        long t = t0;
        for (int guard = 0; guard < Sim.GUARD; guard++) {
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
            long batch = Math.min(r.batch, Sim.get(n.in, in) / per);
            if (batch <= 0) break;
            long room = n.outCap - Sim.total(n.out);
            long each = Math.max(1, Sim.total(r.out));
            batch = Math.min(batch, room / each);
            if (batch <= 0) break;
            Sim.add(n.in, in, -batch * per);
            Sim.addAll(n.pending, r.out, batch);
            n.pendingAt = n.busyUntil = t + batch * r.ticks + r.batchExtra;
            n.made += batch;
        }
    }

    /** A miner: a break every period (regrowth, then its Mk's break time) while it has room and power. */
    static void mine(Node n, long t0, long end) {
        if (n.mineItem.isEmpty() || n.minePeriod <= 0) return;
        if (n.nextBreak == Long.MIN_VALUE) n.nextBreak = t0 + n.minePeriod;
        for (int guard = 0; guard < Sim.MINE_GUARD && n.nextBreak <= end; guard++) {
            if (n.su > 0 && !n.powered) {
                n.nextBreak = end + 1;
                return;
            }
            if (Sim.total(n.out) + (long) Math.ceil(n.minePer) > n.outCap) {
                // full: it waits, and breaks as soon as there is room again
                n.nextBreak = end;
                return;
            }
            n.frac += n.minePer;
            long got = (long) Math.floor(n.frac + Sim.FLOOR_EPSILON);
            n.frac -= got;
            Sim.add(n.out, n.mineItem, got);
            n.mined += got;
            n.made++;
            n.nextBreak += n.minePeriod;
        }
    }
}
