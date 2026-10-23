package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.sim.SimData.Node;
import com.meakaandre.siftec.sim.SimData.Slot;

import java.util.ArrayList;
import java.util.List;

/** A simulated Drone Port: its drone's trips, and the packages it carries out and home. */
final class SimDrones {
    static final int PORT_OUT = 0, PORT_FUEL = 9, PORT_IN = 10, PORT_SIZE = 19;
    static final int IDLE = 0, OUTBOUND = 1, HOMEBOUND = 2;

    private SimDrones() {
    }

    /**
     * A Drone Port, as its block entity does it: every second at home it sends the drone off when packages wait and there
     * are fire charges for the trip; the trip takes its time each way; at the far port it unloads what fits (into that
     * port's simulation, or for the server thread to hand over at a loaded one) and flies home.
     */
    static void port(Node n, long t0, long end, Sim.Group g) {
        Sim.pad(n.slots, PORT_SIZE);
        long t = t0;
        for (int guard = 0; guard < Sim.GUARD && t < end; guard++) {
            if (n.state == IDLE) {
                if (!n.hasDrone || n.destination == null) return;
                long next = (t / 20 + 1) * 20;
                if (next > end) return;
                t = next;
                if (Sim.emptySlots(n.slots, PORT_OUT, PORT_FUEL)) continue;
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
                    long rest = Sim.addSlots(n.slots, PORT_IN, PORT_SIZE, s.key, s.count);
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
            if (far == null || far.kind != NodeKind.DRONE_PORT) {
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
            Sim.pad(far.slots, PORT_SIZE);
            List<Slot> left = new ArrayList<>();
            for (Slot s : n.cargo) {
                long rest = Sim.addSlots(far.slots, PORT_IN, PORT_SIZE, s.key, s.count);
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
}
