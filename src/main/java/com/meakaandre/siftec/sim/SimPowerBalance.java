package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.building.BuildingPower;
import com.meakaandre.siftec.building.PowerRole;
import com.meakaandre.siftec.power.Power;
import com.meakaandre.siftec.power.PowerMember;
import com.meakaandre.siftec.sim.SimData.Node;

import java.util.List;
import java.util.Map;

/** Balances a power grid whose members are all simulated, and the fuel, water and charge that takes. */
final class SimPowerBalance {
    /** What an idle powered building draws (BuildingPower.STANDBY_SU). */
    static final double STANDBY = 1;
    /** Ticks an Alien Power Matrix keeps an Augmenter fed (BuildingPower.MATRIX_TICKS). */
    static final long MATRIX_TICKS = 240;
    /** mB of sulfuric acid the Geothermal Generator leaves a tick while it gives power. */
    static final double ACID_PER_TICK = 50 / 20.0;

    private SimPowerBalance() {
    }

    /** What a consumer asks for over the step: its SU while it has work, its standby draw while it has none. */
    static double demand(Node n, long now) {
        if (n.su <= 0) return 0;
        return working(n, now) ? n.su : STANDBY;
    }

    /** Whether a building has work right now (BuildingLogic.working, as far as the simulation can tell). */
    static boolean working(Node n, long now) {
        return switch (n.mode) {
            case CRAFT -> n.busyUntil > now || SimBuildings.startable(n) != null;
            case SMELT -> !n.pending.isEmpty() || SimBuildings.smeltable(n) != null;
            case MINE -> Sim.total(n.out) + Math.ceil(n.minePer) <= n.outCap;
            case EXTRACT -> Sim.total(n.fout) < n.fluidOutCap;
            case SINK -> !n.in.isEmpty();
            default -> true;
        };
    }

    static boolean generator(Node n) {
        return n.power.givesPower();
    }

    static boolean holdsFuse(Node n) {
        return n.power.holdsFuse();
    }

    /** Energy (SU-ticks) a generator could still give: what its machines hold and what its fuel at the input holds. */
    static double fuelEnergy(Node n) {
        if (n.power.unfuelled()) return Double.MAX_VALUE;
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

    /** How many of this fuel the output still has room for the waste of (NuclearWaste.wasteFits); any number if it leaves none. */
    static long wasteRoom(Node n, String fuel) {
        String waste = n.waste.get(fuel);
        if (waste == null) return Long.MAX_VALUE;
        for (String k : n.out.keySet()) if (!k.equals(waste)) return 0;
        long each = Math.max(1, n.wasteCount.getOrDefault(fuel, 1));
        return Math.max(0, n.outCap - Sim.total(n.out)) / each;
    }

    /** What a generator can give now: its full SU while it has fuel (and water, if it drinks), else nothing. */
    static double capacity(Node n) {
        if (n.tripped || n.fullSu <= 0) return 0;
        if (n.water > 0 && Sim.get(n.fin, Sim.WATER) <= 0) return 0;
        return fuelEnergy(n) > 0 ? n.fullSu : 0;
    }

    /** A generator drinks its water for this many ticks at full power. */
    static void drink(Node n, double ticksAtFull) {
        if (n.water <= 0 || ticksAtFull <= 0) return;
        n.waterFrac += n.water * ticksAtFull;
        long mb = Math.min((long) n.waterFrac, Sim.get(n.fin, Sim.WATER));
        Sim.add(n.fin, Sim.WATER, -mb);
        n.waterFrac -= mb;
        // no more water: it stops owing what it could not drink
        if (Sim.get(n.fin, Sim.WATER) <= 0) n.waterFrac = Math.min(n.waterFrac, 1);
    }

    static double suTicksOfMj(double mj) {
        return mj * Power.SU_PER_MW * 20;
    }

    /** Burns this much of a generator's energy: out of its machines first, then its next fuel. */
    static void burn(Node n, double suTicks) {
        if (suTicks <= 0) return;
        n.given += suTicks;
        if (n.power.unfuelled()) return;
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
                long have = Sim.get(n.fin, fuel);
                long mb = Math.min(have, (long) Math.ceil(-n.energy / per));
                Sim.add(n.fin, fuel, -mb);
                n.energy += mb * per;
                if (mb <= 0) {
                    n.energy = 0;
                    return;
                }
            } else {
                Sim.add(n.in, fuel, -1);
                n.energy += per;
                // a spent fuel rod leaves its waste
                String waste = n.waste.get(fuel);
                if (waste != null) Sim.add(n.out, waste, n.wasteCount.getOrDefault(fuel, 1));
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
            if (n.power == PowerRole.AUGMENTER) {
                if (!n.tripped) {
                    caps[i] = n.fullSu;
                    flat += n.fullSu;
                    boost += n.fed > 0 || hasMatrix(n) ? PowerMember.FED_BOOST : PowerMember.UNFED_BOOST;
                }
            } else if (generator(n)) {
                if (!n.tripped) {
                    caps[i] = Math.min(n.fullSu, fuelEnergy(n) / dt);
                    // a generator that drinks gives only as long as its water lasts
                    if (n.water > 0) caps[i] = Math.min(caps[i], n.fullSu * Sim.get(n.fin, Sim.WATER) / (n.water * dt));
                    boosted += caps[i];
                }
            } else if (n.power == PowerRole.STORAGE) {
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
                if (n.power != PowerRole.STORAGE || n.tripped) continue;
                double w = Math.max(0, Math.min(storageRate(), storageCap() - n.stored) * dt);
                n.stored = Math.min(storageCap(), n.stored + charge * w / chargeWant);
            }
            load = own <= 0 ? 0 : Math.min(1, (demand * dt + charge) / (own * dt));
        } else {
            double shortBy = (demand - own) * dt;
            if (stored > 0 && stored >= shortBy) {
                on = true;
                for (Node n : grid) if (n.power == PowerRole.STORAGE && n.stored > 0) n.stored = Math.max(0, n.stored - shortBy * n.stored / stored);
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
                double give = n.power == PowerRole.NUCLEAR && !n.tripped ? caps[i] : caps[i] * load;
                n.capacityNow = caps[i];
                if (n.power == PowerRole.AUGMENTER) feedAugmenter(n, dt, on);
                else {
                    burn(n, give * dt);
                    if (n.fullSu > 0) drink(n, give * dt / n.fullSu);
                }
                if (n.power == PowerRole.GEOTHERMAL && give > 0 && !n.fluidOut.isEmpty()) {
                    n.frac += ACID_PER_TICK * dt;
                    long mb = (long) n.frac;
                    long room = Math.max(0, n.fluidOutCap - Sim.total(n.fout));
                    Sim.add(n.fout, n.fluidOut, Math.min(mb, room));
                    n.frac -= mb;
                }
            } else if (n.power != PowerRole.STORAGE) {
                n.powered = on || n.su <= 0;
            }
        }
    }

    static double storageCap() {
        return BuildingPower.STORAGE_MWH * Power.SU_PER_MW * Power.TICKS_PER_HOUR;
    }

    static double storageRate() {
        return BuildingPower.STORAGE_CHARGE_MW * Power.SU_PER_MW;
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
                Sim.add(n.in, matrix, -1);
                n.fed = MATRIX_TICKS;
                n.made++;
            }
            long use = Math.min(left, n.fed);
            n.fed -= use;
            left -= use;
        }
    }
}
