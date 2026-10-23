package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.sim.SimData.Node;
import com.meakaandre.siftec.sim.SimData.Slot;
import com.meakaandre.siftec.sim.SimData.Vehicle;
import com.meakaandre.siftec.vehicle.VehicleEntity;

import java.util.ArrayList;
import java.util.List;

/** A simulated vehicle driving its route by time, and loading and unloading at its Truck Stations. */
final class SimRoutes {
    /** A stopped vehicle stands this long at a Truck Station. */
    static final int DOCK_TICKS = VehicleEntity.DOCK_TICKS;
    /** Share of its top speed the autopilot keeps on the straight. */
    private static final double AUTOPILOT_SPEED = 0.8;
    /** Slowest speed (blocks a tick) a leg's time is worked out at, so a stopped kind still gets somewhere. */
    private static final double MIN_SPEED = 0.05;
    /** Ticks added to every worked-out leg for slowing down near its end. */
    private static final int LEG_EXTRA_TICKS = 2;
    /** Ticks short of a leg's time that count as having driven it. */
    private static final double LEG_EPSILON = 1e-6;
    /** Squared length a leg needs for the vehicle to turn to face along it. */
    private static final double MIN_HEADING_SQR = 1e-6;

    private SimRoutes() {
    }

    /** Ticks a leg (to route point {@code to}) takes: as it last drove it for real, or worked out from its length. */
    static double legTicks(Vehicle v, int to) {
        int n = v.points();
        if (n < 2) return 1;
        if (to < v.legTicks.size() && v.legTicks.get(to) > 0) return v.legTicks.get(to);
        int from = (to - 1 + n) % n;
        double dx = v.px(to) - v.px(from), dz = v.pz(to) - v.pz(from);
        double dist = Math.sqrt(dx * dx + dz * dz);
        // the autopilot keeps to 80% of its top speed on the straight, less near a stop
        return Math.max(1, dist / Math.max(MIN_SPEED, v.speed * AUTOPILOT_SPEED)) + LEG_EXTRA_TICKS;
    }

    static int stopIndex(Vehicle v, int point) {
        for (int i = 0; i < v.stopAt.size(); i++) if (v.stopAt.get(i) == point) return i;
        return -1;
    }

    /** A vehicle drives its route by time: each leg takes its ticks and its fuel; at each stop it loads or unloads. */
    static void drive(Vehicle v, long now, Sim.Group g) {
        long dt = now - v.simTime;
        v.simTime = now;
        int n = v.points();
        if (dt <= 0 || n < 2) return;
        double perTick = v.mw / 20.0;
        double left = dt;
        for (int guard = 0; guard < Sim.GUARD && left > 0; guard++) {
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
            if (v.leg + LEG_EPSILON < total) continue;
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
        if (dx * dx + dz * dz > MIN_HEADING_SQR) v.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
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
    static void dock(Vehicle v, int stop, Sim.Group g) {
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
                long take = Math.min(c.count, Math.max(0, s.outCap - Sim.total(s.out)));
                Sim.add(s.out, c.key, take);
                moved += take;
                if (c.count - take > 0) keep.add(new Slot(c.key, c.count - take));
            }
            v.cargo = keep;
        } else {
            List<Slot> cargo = new ArrayList<>(v.cargo);
            while (cargo.size() < v.slots) cargo.add(new Slot());
            for (String key : new ArrayList<>(s.out.keySet())) {
                long have = Sim.get(s.out, key);
                long rest = Sim.addSlots(cargo, 0, v.slots, key, have);
                Sim.add(s.out, key, -(have - rest));
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
            long took = Math.min(room, Sim.get(s.fout, key));
            Sim.add(s.fout, key, -took);
            v.fuel += took * mj;
        }
        v.itemsMoved += moved;
    }
}
