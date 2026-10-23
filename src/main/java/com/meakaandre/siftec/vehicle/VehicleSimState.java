package com.meakaandre.siftec.vehicle;

import com.meakaandre.siftec.sim.Keys;
import com.meakaandre.siftec.sim.SimData;
import com.meakaandre.siftec.sim.SimVehicles;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Copies a vehicle to and from the factory simulation's record of it, to drive it by time ({@link SimVehicles}). */
final class VehicleSimState {
    private VehicleSimState() {
    }

    /** Everything the time-driven vehicle needs, written into the simulation's record of it. */
    static void toSim(VehicleEntity vehicle, SimData.Vehicle v) {
        v.uuid = vehicle.getUUID().toString();
        v.type = vehicle.kind.id;
        v.dim = vehicle.level().dimension().identifier().toString();
        v.company = vehicle.companyId;
        v.fuel = vehicle.fuelMj;
        v.speed = vehicle.kind.maxSpeed;
        v.mw = vehicle.kind.mw;
        v.tank = vehicle.kind.tankMj();
        v.slots = vehicle.kind.slots();
        v.cargo.clear();
        for (int i = 0; i < vehicle.cargo.getContainerSize(); i++) {
            ItemStack s = vehicle.cargo.getItem(i);
            if (!s.isEmpty()) v.cargo.add(new SimData.Slot(Keys.of(s), s.getCount()));
        }
        v.refund.clear();
        for (ItemStack s : vehicle.refund) if (!s.isEmpty()) v.refund.add(new SimData.Slot(Keys.of(s), s.getCount()));
        v.route.clear();
        for (Vec3 p : vehicle.route) {
            v.route.add(p.x);
            v.route.add(p.y);
            v.route.add(p.z);
        }
        v.stopAt.clear();
        v.stopAt.addAll(vehicle.stopAt);
        v.stopStation.clear();
        for (BlockPos p : vehicle.stopStation) v.stopStation.add(p.asLong());
        v.legTicks.clear();
        v.legTicks.addAll(vehicle.legTicks);
        v.target = Math.max(0, Math.min(vehicle.target, vehicle.route.size() - 1));
        v.lastStation = vehicle.lastStation == null ? null : vehicle.lastStation.asLong();
        v.stopsMade = vehicle.stopsMade;
        v.itemsMoved = vehicle.itemsMoved;
        v.docked = vehicle.docked > 0 ? vehicle.docked : -1;
        v.x = vehicle.getX();
        v.y = vehicle.getY();
        v.z = vehicle.getZ();
        v.yaw = vehicle.getYRot();
        // how far along its leg it is, by distance
        int n = vehicle.route.size();
        Vec3 to = vehicle.route.get(v.target), from = vehicle.route.get((v.target - 1 + n) % n);
        double leg = from.distanceTo(to), left = vehicle.position().distanceTo(to);
        v.leg = leg <= 0 ? 0 : Math.max(0, Math.min(1, 1 - left / leg)) * SimVehicles.legTicks(v, v.target);
    }

    /** Puts a vehicle the simulation drove back into the world as it left it. */
    static void fromSim(VehicleEntity vehicle, SimData.Vehicle v) {
        vehicle.companyId = v.company;
        vehicle.fuelMj = v.fuel;
        for (int i = 0; i < vehicle.cargo.getContainerSize(); i++) vehicle.cargo.setItem(i, ItemStack.EMPTY);
        int slot = 0;
        for (var s : v.cargo) {
            ItemStack stack = Keys.stack(s.key, s.count);
            while (!stack.isEmpty() && slot < vehicle.cargo.getContainerSize()) {
                ItemStack part = stack.split(stack.getMaxStackSize());
                vehicle.cargo.setItem(slot++, part);
            }
        }
        vehicle.refund.clear();
        for (var s : v.refund) {
            ItemStack stack = Keys.stack(s.key, s.count);
            if (!stack.isEmpty()) vehicle.refund.add(stack);
        }
        vehicle.route.clear();
        for (int i = 0; i + 2 < v.route.size(); i += 3)
            vehicle.route.add(new Vec3(v.route.get(i), v.route.get(i + 1), v.route.get(i + 2)));
        vehicle.stopAt.clear();
        vehicle.stopAt.addAll(v.stopAt);
        vehicle.stopStation.clear();
        for (long p : v.stopStation) vehicle.stopStation.add(BlockPos.of(p));
        vehicle.legTicks.clear();
        vehicle.legTicks.addAll(v.legTicks);
        vehicle.target = vehicle.route.isEmpty() ? 0 : Math.max(0, Math.min(v.target, vehicle.route.size() - 1));
        vehicle.lastStation = v.lastStation == null ? null : BlockPos.of(v.lastStation);
        vehicle.stopsMade = v.stopsMade;
        vehicle.itemsMoved = v.itemsMoved;
        vehicle.recording = false;
        // standing at a stop it already loaded or unloaded at: it waits out the rest there without doing it again
        vehicle.docked = v.docked >= 0 ? Math.max(1, v.docked) : 0;
        vehicle.lastDist = Double.MAX_VALUE;
        vehicle.stuck = 0;
        vehicle.legStart = -1;
        vehicle.speed = 0;
        vehicle.syncFuel();
    }
}
