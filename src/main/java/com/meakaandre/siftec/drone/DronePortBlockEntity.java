package com.meakaandre.siftec.drone;

import com.meakaandre.siftec.building.BuildingIndex;
import com.meakaandre.siftec.registry.ModItems;
import com.meakaandre.siftec.sim.Factories;
import com.meakaandre.siftec.sim.Keys;
import com.meakaandre.siftec.sim.SimData;
import com.meakaandre.siftec.util.Chunks;
import com.meakaandre.siftec.util.Drops;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Drone Port's loading terminal, under the landing pad of the Drone Port building, with one Cardboard Drone. When
 * packages are waiting and there are fire charges for the trip, the drone takes off from the pad, carries every waiting
 * package to the chosen port's pad, unloads, and flies home to land on its own pad again. The trip is worked out by time,
 * so it goes on over land that is not loaded; the drone you see ({@link DroneEntity}) is only drawn where it can be. A
 * far port whose chunk does not tick takes the packages all the same: the factory simulation unloads them into it (work
 * package 17), and while this port's own chunk does not tick the simulation flies its drone (SimData's drone ports).
 * <p>
 * A port is only ever the Drone Port building's terminal: one that is no building's does nothing.
 */
public class DronePortBlockEntity extends BlockEntity {
    public static final int IDLE = 0, OUTBOUND = 1, HOMEBOUND = 2;
    /** Blocks per tick, and the fire charges burned per this many blocks of a round trip. */
    private static final double SPEED = 0.8;
    private static final int BLOCKS_PER_CHARGE = 100, GROUND_TICKS = 40, CRUISE = 12;
    /** Ticks the drone takes to climb off its pad, and to come down onto the other: half of GROUND_TICKS each. */
    private static final int CLIMB_TICKS = GROUND_TICKS / 2;
    /** The landing pad's deck is this far over the terminal, straight up (tools/structures.py, drone_port). */
    public static final int PAD_DY = 10;

    public String companyId = "";
    public final PortContainer items = new PortContainer(this::setChanged);
    public boolean hasDrone;
    public @Nullable BlockPos destination;
    public int state = IDLE;
    private int ticks, total;
    private final List<ItemStack> cargo = new ArrayList<>();
    private @Nullable DroneEntity shown;

    public DronePortBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public int chargesNeeded() {
        if (destination == null) return 0;
        // never more than the one stack the port can hold
        return Math.clamp((int) Math.ceil(Math.sqrt(destination.distSqr(worldPosition)) * 2 / BLOCKS_PER_CHARGE), 1, 64);
    }

    public String statusKey() {
        if (!hasDrone) return "siftec.drone.no_drone";
        if (destination == null) return "siftec.drone.no_destination";
        if (state == OUTBOUND) return ticks > 0 ? "siftec.drone.outbound" : "siftec.drone.waiting";
        if (state == HOMEBOUND) return "siftec.drone.homebound";
        if (items.empty(PortContainer.OUT, PortContainer.FUEL)) return "siftec.drone.idle";
        return items.getItem(PortContainer.FUEL).getCount() < chargesNeeded() ? "siftec.drone.no_fuel" : "siftec.drone.idle";
    }

    /** Ticks a trip takes each way: the distance at the drone's speed, plus its take-off and landing. */
    public int flightTicks() {
        return destination == null ? 0 : (int) (Math.sqrt(destination.distSqr(worldPosition)) / SPEED) + GROUND_TICKS;
    }

    public void serverTick() {
        if (!(level instanceof ServerLevel server)) return;
        // what the factory simulation did while its chunk did not tick goes in before it runs for real
        Factories.awake(server, this);
        // a port is only ever the Drone Port building's terminal: one standing on its own (put down by a command) does nothing
        if (BuildingIndex.controllerOf(server, worldPosition) == null) return;
        if (server.getGameTime() % 20 == 0) Factories.port(server, this);
        if (state == IDLE) {
            // a drone at home sits on the pad
            if (hasDrone) park(server);
            else if (shown != null) hide();
            if (!hasDrone || destination == null || level.getGameTime() % 20 != 0 || items.empty(PortContainer.OUT, PortContainer.FUEL)) return;
            int charges = chargesNeeded();
            if (items.getItem(PortContainer.FUEL).getCount() < charges) return;
            items.removeItem(PortContainer.FUEL, charges);
            for (int i = PortContainer.OUT; i < PortContainer.FUEL; i++) {
                if (!items.getItem(i).isEmpty()) cargo.add(items.removeItemNoUpdate(i));
            }
            total = ticks = flightTicks();
            state = OUTBOUND;
            setChanged();
            return;
        }
        if (ticks > 0) ticks--;
        show(server);
        if (ticks > 0) return;
        if (state == HOMEBOUND) {
            // anything that could not be delivered comes back as an arrival; what fits nowhere stays held in the port
            // (it is dropped only if the port is broken)
            for (int i = 0; i < cargo.size(); i++) cargo.set(i, items.add(cargo.get(i), PortContainer.IN, PortContainer.SIZE));
            cargo.removeIf(ItemStack::isEmpty);
            state = IDLE;
            hide();
            setChanged();
            return;
        }
        // at the far port: unload what fits, once a second; wait for room
        if (level.getGameTime() % 20 != 0 || destination == null) return;
        boolean ticking = Chunks.ticking(server, destination);
        if (!ticking) {
            // its chunk does not tick: the simulation's record of that port takes the packages
            int known = Factories.deliverToPort(server, destination, cargo);
            if (known >= 0) {
                setChanged();
                if (!cargo.isEmpty()) return;
                state = HOMEBOUND;
                ticks = total;
                setChanged();
                return;
            }
            if (!level.isLoaded(destination)) {
                // no port is known there: nothing to unload into, so it comes home with the packages
                state = HOMEBOUND;
                ticks = total;
                setChanged();
                return;
            }
        }
        if (level.getBlockEntity(destination) instanceof DronePortBlockEntity other) {
            for (int i = 0; i < cargo.size(); i++) cargo.set(i, other.items.add(cargo.get(i), PortContainer.IN, PortContainer.SIZE));
            cargo.removeIf(ItemStack::isEmpty);
            if (!cargo.isEmpty()) return;
        }
        state = HOMEBOUND;
        ticks = total;
        setChanged();
    }

    /** Ends the flight under way at once (the selftests: a long trip without the wait). */
    public void land() {
        if (state != IDLE) ticks = 0;
    }

    /** The middle of this port's landing pad, where its drone sits and takes off from. */
    public Vec3 pad() {
        return padOf(worldPosition);
    }

    /** The middle of the landing pad over a port's terminal (straight over it, whichever way the port faces). */
    public static Vec3 padOf(BlockPos terminal) {
        return new Vec3(terminal.getX() + 0.5, terminal.getY() + PAD_DY, terminal.getZ() + 0.5);
    }

    /**
     * Where the drone is this many ticks into a trip from one pad to another taking {@code total} ticks: it climbs
     * straight up off the first pad, flies over at cruising height, and comes straight down onto the other.
     */
    public static Vec3 along(Vec3 from, Vec3 to, int elapsed, int total) {
        int cruise = Math.max(1, total - 2 * CLIMB_TICKS);
        if (elapsed <= CLIMB_TICKS) {
            double up = ease(elapsed / (double) CLIMB_TICKS);
            return new Vec3(from.x, from.y + CRUISE * up, from.z);
        }
        if (elapsed >= total - CLIMB_TICKS) {
            double up = ease(Math.max(0, total - elapsed) / (double) CLIMB_TICKS);
            return new Vec3(to.x, to.y + CRUISE * up, to.z);
        }
        double t = Math.min(1, (elapsed - CLIMB_TICKS) / (double) cruise);
        return new Vec3(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t + CRUISE, from.z + (to.z - from.z) * t);
    }

    private static double ease(double t) {
        t = Math.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** Where the drone is on its way, for drawing it. */
    private void show(ServerLevel server) {
        if (destination == null) return;
        Vec3 home = pad(), away = padOf(destination);
        boolean out = state == OUTBOUND;
        Vec3 from = out ? home : away, to = out ? away : home;
        int elapsed = total - ticks;
        Vec3 at = ticks <= 0 && out ? away : along(from, to, elapsed, total);
        float yaw = (float) (Math.atan2(-(to.x - from.x), to.z - from.z) * Mth.RAD_TO_DEG);
        place(server, at, yaw, ticks > 0, !cargo.isEmpty());
    }

    /** The drone at home on its pad, its propellers still. */
    private void park(ServerLevel server) {
        place(server, pad(), shown != null ? shown.getYRot() : 0, false, false);
    }

    private void place(ServerLevel server, Vec3 at, float yaw, boolean flying, boolean loaded) {
        if (!server.isLoaded(BlockPos.containing(at))) {
            hide();
            return;
        }
        if (shown == null || shown.isRemoved()) {
            DroneEntity drone = DroneEntity.TYPE.create(server, EntitySpawnReason.TRIGGERED);
            if (drone == null) return;
            drone.snapTo(at.x, at.y, at.z, yaw, 0);
            server.addFreshEntity(drone);
            shown = drone;
        }
        shown.setPos(at.x, at.y, at.z);
        shown.setYRot(yaw);
        shown.set(flying, loaded);
    }

    private void hide() {
        if (shown != null) shown.discard();
        shown = null;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        hide();
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level == null) return;
        // broken (whether its chunk ticks or not): the factory simulation forgets it, so it is never run on with what it held
        if (level instanceof ServerLevel server) Factories.removed(server, pos);
        Drops.all(level, pos, 0.5, contents());
        if (hasDrone) Drops.at(level, pos, 0.5, new ItemStack(ModItems.CARDBOARD_DRONE.get()));
        items.clearContent();
        cargo.clear();
        hasDrone = false;
    }

    /** Everything the port holds, for when it is broken. */
    public List<ItemStack> contents() {
        List<ItemStack> out = new ArrayList<>(cargo);
        for (int i = 0; i < PortContainer.SIZE; i++) if (!items.getItem(i).isEmpty()) out.add(items.getItem(i));
        return out;
    }

    @Override
    protected void saveAdditional(ValueOutput view) {
        super.saveAdditional(view);
        view.putString("Company", companyId);
        view.putBoolean("Drone", hasDrone);
        view.putInt("State", state);
        view.putInt("Ticks", ticks);
        view.putInt("Total", total);
        if (destination != null) view.store("Destination", BlockPos.CODEC, destination);
        List<ItemStack> slots = new ArrayList<>();
        for (int i = 0; i < PortContainer.SIZE; i++) slots.add(items.getItem(i));
        view.store("Items", ItemStack.OPTIONAL_CODEC.listOf(), slots);
        view.store("Cargo", ItemStack.OPTIONAL_CODEC.listOf(), List.copyOf(cargo));
    }

    @Override
    protected void loadAdditional(ValueInput view) {
        super.loadAdditional(view);
        companyId = view.getStringOr("Company", "");
        hasDrone = view.getBooleanOr("Drone", false);
        state = view.getIntOr("State", IDLE);
        ticks = view.getIntOr("Ticks", 0);
        total = view.getIntOr("Total", 0);
        destination = view.read("Destination", BlockPos.CODEC).orElse(null);
        List<ItemStack> slots = view.read("Items", ItemStack.OPTIONAL_CODEC.listOf()).orElse(List.of());
        for (int i = 0; i < PortContainer.SIZE; i++) items.setItem(i, i < slots.size() ? slots.get(i) : ItemStack.EMPTY);
        cargo.clear();
        cargo.addAll(view.read("Cargo", ItemStack.OPTIONAL_CODEC.listOf()).orElse(List.of()));
        cargo.removeIf(ItemStack::isEmpty);
    }

    // ------------------------------------------------------------------------------------------------ the simulation

    /** Its slots, cargo and flight, into the simulation's record (its chunk stops ticking). */
    public void toSim(SimData.Node n) {
        n.slots.clear();
        for (int i = 0; i < PortContainer.SIZE; i++) {
            ItemStack s = items.getItem(i);
            n.slots.add(s.isEmpty() ? new SimData.Slot()
                : new SimData.Slot(Keys.of(s), s.getCount()));
        }
        n.cargo.clear();
        for (ItemStack s : cargo) if (!s.isEmpty()) n.cargo.add(new SimData.Slot(Keys.of(s), s.getCount()));
        flight(n);
    }

    /** Its flight and settings (all the map needs), kept up to date while it ticks. */
    public void flight(SimData.Node n) {
        n.company = companyId;
        n.hasDrone = hasDrone;
        n.destination = destination == null ? null : destination.asLong();
        n.state = state;
        n.ticks = ticks;
        n.total = total;
        n.charges = chargesNeeded();
        n.flight = flightTicks();
    }

    /**
     * What the simulation did while its chunk did not tick, back into the port: in its slots only what changed in the
     * simulation since it took the port over (so what a player took out meanwhile, or a drone brought, stays as it is);
     * the drone's cargo and flight as the simulation has them.
     */
    public void fromSim(SimData.Node n) {
        List<ItemStack> spill = new ArrayList<>();
        spill.addAll(applyDelta(n.slots, n.baseSlots, PortContainer.OUT, PortContainer.FUEL));
        spill.addAll(applyDelta(n.slots, n.baseSlots, PortContainer.FUEL, PortContainer.FUEL + 1));
        spill.addAll(applyDelta(n.slots, n.baseSlots, PortContainer.IN, PortContainer.SIZE));
        if (level != null) Drops.all(level, worldPosition, 1.1, spill);
        cargo.clear();
        for (var s : n.cargo) {
            ItemStack stack = Keys.stack(s.key, s.count);
            if (!stack.isEmpty()) cargo.add(stack);
        }
        state = n.state;
        ticks = n.ticks;
        total = n.total;
        setChanged();
    }

    /** Slots from..to change by what the simulation's slots hold against what they held when it took over; returns what did not fit. */
    private List<ItemStack> applyDelta(List<SimData.Slot> now, List<SimData.Slot> base, int from, int to) {
        Map<String, Long> d = new LinkedHashMap<>();
        for (int i = from; i < to; i++) {
            if (i < now.size() && !now.get(i).empty()) d.merge(now.get(i).key, now.get(i).count, Long::sum);
            if (i < base.size() && !base.get(i).empty()) d.merge(base.get(i).key, -base.get(i).count, Long::sum);
        }
        List<ItemStack> spill = new ArrayList<>();
        for (var e : d.entrySet()) {
            long take = -e.getValue();
            for (int i = from; i < to && take > 0; i++) {
                ItemStack stack = items.getItem(i);
                if (stack.isEmpty() || !Keys.of(stack).equals(e.getKey())) continue;
                int n = (int) Math.min(take, stack.getCount());
                items.removeItem(i, n);
                take -= n;
            }
        }
        for (var e : d.entrySet()) {
            long give = e.getValue();
            while (give > 0) {
                ItemStack one = Keys.stack(e.getKey(), 1);
                if (one.isEmpty()) break;
                int n = (int) Math.min(give, one.getMaxStackSize());
                ItemStack rest = items.add(one.copyWithCount(n), from, to);
                if (!rest.isEmpty()) spill.add(rest);
                give -= n;
            }
        }
        setChanged();
        return spill;
    }

    /** Gives back the drone as an item, if it is home. */
    public ItemStack takeDrone() {
        if (!hasDrone || state != IDLE) return ItemStack.EMPTY;
        hasDrone = false;
        setChanged();
        return new ItemStack(ModItems.CARDBOARD_DRONE.get());
    }

    public ItemStack fuel() {
        return items.getItem(PortContainer.FUEL);
    }

    public static boolean isFuel(ItemStack stack) {
        return stack.is(Items.FIRE_CHARGE);
    }
}
