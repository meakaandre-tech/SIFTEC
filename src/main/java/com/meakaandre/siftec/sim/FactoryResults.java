package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.building.PowerRole;
import com.meakaandre.siftec.building.SimAccess;
import com.meakaandre.siftec.drone.DronePortBlockEntity;
import com.meakaandre.siftec.drone.PortContainer;
import com.meakaandre.siftec.power.PowerMemory;
import com.meakaandre.siftec.sim.SimData.Link;
import com.meakaandre.siftec.sim.SimData.Node;
import com.meakaandre.siftec.sink.Sink;
import com.meakaandre.siftec.util.Chunks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Applies a finished step in the world: deliveries, Sink points, drones and trucks at ticking stations, power. */
final class FactoryResults {
    private FactoryResults() {
    }

    /** After a step: deliveries to ticking buildings, Sink points, drones and trucks at ticking stations, power memory. */
    static void applyResults(MinecraftServer s) {
        long t0 = System.nanoTime();
        SimData data = SimData.get(s);
        long now = s.overworld().getGameTime();
        for (Sim.Group g : Factories.runningGroups) {
            for (Node n : g.nodes) {
                if (!n.sunk.isEmpty()) pay(s, n);
                if (n.handOver) handOver(s, data, n);
            }
            for (Link l : g.links) {
                Node to = data.node(l.to);
                if (to == null || to.sim) continue;
                ServerLevel level = FactoryChunks.level(s, l.dim);
                if (level == null || !FactoryChunks.loaded(level, to)) continue;
                var it = l.transit.iterator();
                while (it.hasNext()) {
                    SimData.Transit t = it.next();
                    if (t.at > now) break;
                    long put = Buffers.insert(level, to, t.key, t.n, l.fluid);
                    t.n -= put;
                    if (t.n > 0) break;
                    it.remove();
                }
            }
            for (SimData.Vehicle v : g.vehicles) if (v.dockPending >= 0) dockReal(s, data, v);
            // a generator or Power Storage in a grid the simulation balances by itself: its memory follows the simulation
            for (List<Node> grid : g.grids) for (Node n : grid) syncMemory(s, n, false);
        }
        for (Map.Entry<String, Long> e : FactoryGroups.MIXED.entrySet()) {
            Node n = data.node(e.getKey());
            ServerLevel level = n == null ? null : FactoryChunks.level(s, n.dim);
            if (n == null || level == null || !n.sim) continue;
            // a generator or Power Storage in such a grid is its remembered self (PowerGrids burns and charges it; the
            // simulation burns what it gave from the fuel it has, before each step); a consumer asks for what it needs
            if (SimPowerBalance.generator(n) || n.power == PowerRole.STORAGE) continue;
            PowerMemory.Remembered r = PowerMemory.get(level).at(level, n.blockPos());
            if (r != null) r.simulateDemand(SimPowerBalance.demand(n, now));
        }
        data.setDirty();
        Factories.mainLastNanos += System.nanoTime() - t0;
    }

    /**
     * Keeps a simulated generator's or Power Storage's power memory and the simulation in step. In a mixed grid (balanced
     * by PowerGrids) what its stand-in gave is burnt from the simulation's fuel, its stand-in is told what that fuel now
     * holds (belts bring more), and its charge and fuse are the grid's; in a grid the simulation balances by itself its
     * memory is told the simulation's fuel, charge and fuse, so either side always starts from the other's last state.
     */
    static void syncMemory(MinecraftServer s, Node n, boolean mixed) {
        if (!n.sim || !SimPowerBalance.holdsFuse(n)) return;
        ServerLevel level = FactoryChunks.level(s, n.dim);
        PowerMemory.Remembered r = level == null ? null : PowerMemory.get(level).at(level, n.blockPos());
        if (r == null) return;
        boolean fuel = n.power.burnsFuel();
        double spent = r.takeSpent();
        if (spent > 0 && fuel) {
            SimPowerBalance.burn(n, spent);
            if (n.fullSu > 0) SimPowerBalance.drink(n, spent / n.fullSu);
        }
        double cap = fuel ? SimPowerBalance.capacity(n) : -1, reserve = fuel ? SimPowerBalance.fuelEnergy(n) : -1;
        if (mixed) {
            if (fuel) r.simFuel(cap, reserve);
            if (n.power == PowerRole.STORAGE) n.stored = r.stored();
            n.tripped = r.tripped();
        } else {
            r.fromSim(cap, reserve, n.stored, n.tripped);
        }
    }

    private static void pay(MinecraftServer s, Node n) {
        long points = 0;
        for (Map.Entry<String, Long> e : n.sunk.entrySet()) {
            Long worth = n.worth.get(e.getKey());
            points += (worth == null ? SimAccess.worth(e.getKey()) : worth) * e.getValue();
        }
        n.sunk.clear();
        if (points > 0 && !n.company.isEmpty()) Sink.paySimulated(s, n.company, points);
    }

    /** A simulated drone at a ticking port: the packages go into that port's arrivals, and the drone heads home. */
    private static void handOver(MinecraftServer s, SimData data, Node n) {
        n.handOver = false;
        ServerLevel level = FactoryChunks.level(s, n.dim);
        if (level == null || n.destination == null) return;
        BlockPos at = BlockPos.of(n.destination);
        if (!(Chunks.blockEntity(level, at) instanceof DronePortBlockEntity other)) return;
        List<SimData.Slot> left = new ArrayList<>();
        for (SimData.Slot c : n.cargo) {
            ItemStack rest = other.items.add(Keys.stack(c.key, c.count), PortContainer.IN, PortContainer.SIZE);
            if (!rest.isEmpty()) left.add(new SimData.Slot(c.key, rest.getCount()));
        }
        n.cargo = left;
        if (left.isEmpty()) {
            n.state = SimDrones.HOMEBOUND;
            n.ticks = n.total;
        }
        data.setDirty();
    }

    /**
     * A simulated vehicle stopped at a Truck Station whose chunk ticks (the route point it stands at does not): the station
     * unloads it into its vaults, or loads it from them, and fills its tank, in the world.
     */
    private static void dockReal(MinecraftServer s, SimData data, SimData.Vehicle v) {
        int stop = v.dockPending;
        v.dockPending = -1;
        if (stop < 0 || stop >= v.stopStation.size()) return;
        ServerLevel level = FactoryChunks.level(s, v.dim);
        Node st = data.node(SimData.key(v.dim, v.stopStation.get(stop)));
        if (level == null || st == null || st.sim || !FactoryChunks.loaded(level, st)) return;
        long moved = 0;
        if (st.unload) {
            List<SimData.Slot> keep = new ArrayList<>();
            for (SimData.Slot c : v.cargo) {
                if (c.empty()) continue;
                long put = Buffers.insert(level, st, c.key, c.count, false);
                moved += put;
                if (c.count - put > 0) keep.add(new SimData.Slot(c.key, c.count - put));
            }
            v.cargo = keep;
        } else {
            List<SimData.Slot> cargo = new ArrayList<>(v.cargo);
            while (cargo.size() < v.slots) cargo.add(new SimData.Slot());
            long room = 0;
            for (SimData.Slot c : cargo) room += c.empty() ? Keys.STACK : Math.max(0, Keys.max(c.key) - c.count);
            Map<String, Long> got = Buffers.extract(level, st, room, false);
            for (Map.Entry<String, Long> e : got.entrySet()) {
                long rest = Sim.addSlots(cargo, 0, v.slots, e.getKey(), e.getValue());
                moved += e.getValue() - rest;
                // what does not fit after all (a smaller stack size) goes back
                if (rest > 0) Buffers.putBack(level, st, e.getKey(), rest, false);
            }
            cargo.removeIf(SimData.Slot::empty);
            v.cargo = cargo;
        }
        // the station's fuel tank fills the vehicle's
        for (String fuel : st.fuelMj.keySet()) {
            Double mj = st.fuelMj.get(fuel);
            if (mj == null || mj <= 0) continue;
            long want = (long) Math.floor((v.tank - v.fuel) / mj);
            if (want <= 0) break;
            Map<String, Long> took = Buffers.extract(level, st, want, true);
            for (Map.Entry<String, Long> e : took.entrySet()) {
                Double per = st.fuelMj.get(e.getKey());
                if (per != null && per > 0) v.fuel = Math.min(v.tank, v.fuel + e.getValue() * per);
                else Buffers.putBack(level, st, e.getKey(), e.getValue(), true);
            }
        }
        v.itemsMoved += moved;
        data.setDirty();
    }
}
