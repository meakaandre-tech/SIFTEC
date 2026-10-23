package com.meakaandre.siftec.vehicle;

import com.meakaandre.siftec.building.BuildGun;
import com.meakaandre.siftec.building.BuildGunItem;
import com.meakaandre.siftec.building.BuildingMenus;
import com.meakaandre.siftec.building.TruckStations;
import com.meakaandre.siftec.sim.SimData;
import com.meakaandre.siftec.sim.SimVehicles;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A Tractor, Truck or Explorer ({@link VehicleKind}): one entity, moved by the server, drawn by
 * {@code client.VehicleRenderer} from Create's own models. Use it to get in, sneak-use it to open its storage, use it
 * with a bucket of fuel to fill its tank. It burns its kind's MW of fuel while its throttle is open.
 * <p>
 * A route: while driving, V starts recording; the vehicle notes a point every couple of blocks and a stop at each
 * Truck Station it passes the dock of; V again finishes. With a route and nobody in the driver's seat it drives the
 * route by itself, in a loop, standing at each stop's dock for {@link #DOCK_TICKS} while the station loads or unloads
 * it and fills its tank ({@link TruckStations#dock}).
 */
public class VehicleEntity extends Entity {
    static final EntityDataAccessor<Float> SPEED = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> FUEL = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.INT);
    /** How long a vehicle on a route stands at a stop. */
    public static final int DOCK_TICKS = 100;
    /** Ticks between the driver's HUD updates, and between "no fuel" reminders. */
    private static final int HUD_INTERVAL_TICKS = 10, NO_FUEL_MESSAGE_TICKS = 40;

    public final VehicleKind kind;
    public final SimpleContainer cargo;
    public final List<ItemStack> refund = new ArrayList<>();
    public String companyId = "";
    /** Megajoules of fuel in its tank. */
    public double fuelMj;
    /** Blocks a tick, forward positive. */
    double speed;

    // ---- the route
    public final List<Vec3> route = new ArrayList<>();
    /** Stops: the route point it stops at, and the Truck Station whose dock that is. */
    public final List<Integer> stopAt = new ArrayList<>();
    public final List<BlockPos> stopStation = new ArrayList<>();
    public boolean recording;
    /** The route point it is driving to. */
    public int target;
    /** The Truck Station it last stopped at (where it goes back to if it falls out of the world); null if none yet. */
    public @Nullable BlockPos lastStation;
    int docked, stuck;
    double lastDist = Double.MAX_VALUE;
    /**
     * Ticks each leg of the route took the last time it drove it for real (index: the point the leg ends at), so the
     * factory simulation drives it by the same times while its land does not tick.
     */
    public final List<Integer> legTicks = new ArrayList<>();
    /** The tick it set off on the leg it is driving. */
    int legStart = -1;
    /** A selftest's pretend driver: the keys it holds while nobody sits in the seat (null: none). */
    public @Nullable VehicleInputPayload pilot;
    /** Stops it has made by itself, and loads it has moved there (items), for the selftests. */
    public int stopsMade;
    public long itemsMoved;

    // ---- client only: how far it has rolled (blocks), turning the wheels and running the treads
    public float roll, rollO;
    private double lastX = Double.NaN, lastZ;

    public VehicleEntity(EntityType<? extends VehicleEntity> type, Level level) {
        super(type, level);
        this.kind = Vehicles.kindOf(type);
        this.cargo = new SimpleContainer(kind.slots());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(SPEED, 0f);
        builder.define(FUEL, 0);
    }

    /** Its speed as the clients see it (blocks a tick). */
    public float syncedSpeed() {
        return entityData.get(SPEED);
    }

    /** Fuel left, 0 to 100. */
    public int fuelPercent() {
        return entityData.get(FUEL);
    }

    public Vec3 forward() {
        float yaw = getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
    }

    /** A point in the vehicle's own frame (x right, y up, z forward, from the middle of its bottom) in the world. */
    public Vec3 local(double x, double y, double z) {
        float yaw = getYRot() * Mth.DEG_TO_RAD;
        double sin = Mth.sin(yaw), cos = Mth.cos(yaw);
        // forward is (-sin, 0, cos); right is forward turned a quarter clockwise seen from above: (-cos, 0, -sin)
        return new Vec3(getX() - sin * z - cos * x, getY() + y, getZ() + cos * z - sin * x);
    }

    // ------------------------------------------------------------------------------------------------ its body

    /**
     * True while it moves itself: its collision box is then only the square middle of its body (see
     * {@link VehicleDriving#drive}).
     */
    boolean moving;

    /**
     * Its box: the whole of its turned body (its model's width and length), as the smallest box round it, so the game
     * finds it to pick, hit and interact with along all its length; picking it is then narrowed to the body itself
     * ({@code mixin.VehiclePickMixin}). While it moves itself it collides as the square middle of its body, and its
     * ends are kept out of walls ({@link VehicleDriving#bodyBlocked}).
     */
    @Override
    protected AABB makeBoundingBox(Vec3 pos) {
        if (kind == null) return super.makeBoundingBox(pos);
        double hw = kind.boxWidth / 2.0;
        if (moving) return new AABB(pos.x - hw, pos.y, pos.z - hw, pos.x + hw, pos.y + kind.boxHeight, pos.z + hw);
        float yaw = getYRot() * Mth.DEG_TO_RAD;
        double sin = Math.abs(Mth.sin(yaw)), cos = Math.abs(Mth.cos(yaw)), hl = kind.boxLength / 2.0;
        double ex = cos * hw + sin * hl, ez = sin * hw + cos * hl;
        return new AABB(pos.x - ex, pos.y, pos.z - ez, pos.x + ex, pos.y + kind.boxHeight, pos.z + ez);
    }

    /** A point in the world in the vehicle's own frame (the inverse of {@link #local}). */
    public Vec3 toLocal(Vec3 world) {
        float yaw = getYRot() * Mth.DEG_TO_RAD;
        double sin = Mth.sin(yaw), cos = Mth.cos(yaw), dx = world.x - getX(), dz = world.z - getZ();
        return new Vec3(-cos * dx - sin * dz, world.y - getY(), -sin * dx + cos * dz);
    }

    /** Its body in its own frame, grown on every side by {@code grow}. */
    private AABB body(double grow) {
        double hw = kind.boxWidth / 2.0 + grow, hl = kind.boxLength / 2.0 + grow;
        return new AABB(-hw, -grow, -hl, hw, kind.boxHeight + grow, hl);
    }

    /** Where the line from {@code from} to {@code to} first meets its turned body (grown by {@code grow}), if it does. */
    public Optional<Vec3> bodyHit(Vec3 from, Vec3 to, double grow) {
        return body(grow).clip(toLocal(from), toLocal(to)).map(p -> local(p.x, p.y, p.z));
    }

    /** True if a point is inside its turned body. */
    public boolean bodyContains(Vec3 point, double grow) {
        return body(grow).contains(toLocal(point));
    }

    /** The eight corners of its turned body, bottom four first (front left, front right, back right, back left), for outlines. */
    public Vec3[] bodyCorners(double grow) {
        AABB b = body(grow);
        Vec3[] out = new Vec3[8];
        double[][] xz = {{b.minX, b.maxZ}, {b.maxX, b.maxZ}, {b.maxX, b.minZ}, {b.minX, b.minZ}};
        for (int i = 0; i < 4; i++) {
            out[i] = local(xz[i][0], b.minY, xz[i][1]);
            out[i + 4] = local(xz[i][0], b.maxY, xz[i][1]);
        }
        return out;
    }

    public void addFuel(double mj) {
        fuelMj = Math.min(kind.tankMj(), fuelMj + mj);
        syncFuel();
    }

    void syncFuel() {
        int percent = (int) Math.ceil(100 * fuelMj / kind.tankMj());
        if (entityData.get(FUEL) != percent) entityData.set(FUEL, percent);
    }

    // ------------------------------------------------------------------------------------------------ ticking

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            // its box follows its heading as it turns
            setBoundingBox(makeBoundingBox());
            rollO = roll;
            if (!Double.isNaN(lastX)) {
                Vec3 f = forward();
                roll += (float) ((getX() - lastX) * f.x + (getZ() - lastZ) * f.z);
            }
            lastX = getX();
            lastZ = getZ();
            return;
        }
        ServerLevel level = (ServerLevel) level();
        ServerPlayer driver = getFirstPassenger() instanceof ServerPlayer p ? p : null;
        double throttle = 0, steer = 0;
        boolean brake = false;
        VehicleInputPayload keys = driver != null ? Vehicles.input(driver) : pilot;
        if (keys != null) {
            throttle = (keys.forward() ? 1 : 0) - (keys.back() ? 1 : 0);
            steer = (keys.right() ? 1 : 0) - (keys.left() ? 1 : 0);
            brake = keys.brake();
            docked = 0;
            if (recording) VehicleRecording.record(this, level);
            if (driver != null && tickCount % HUD_INTERVAL_TICKS == 0) hud(driver);
        } else if (!recording && route.size() >= 2) {
            double[] auto = VehicleAutopilot.autopilot(this, level);
            throttle = auto[0];
            steer = auto[1];
            brake = auto[2] > 0;
        }
        if (fuelMj <= 0 && throttle != 0) {
            throttle = 0;
            if (driver != null && tickCount % NO_FUEL_MESSAGE_TICKS == 0)
                driver.sendOverlayMessage(Component.translatable("siftec.vehicle.no_fuel"));
        }
        VehicleDriving.drive(this, throttle, steer, brake);
        if (throttle != 0) {
            fuelMj = Math.max(0, fuelMj - kind.mw / 20.0);
            syncFuel();
        }
    }

    @Override
    public float maxUpStep() {
        return 1.0f;
    }

    private void hud(ServerPlayer driver) {
        Component state = recording ? Component.translatable("siftec.vehicle.recording", route.size(), stopAt.size())
            : route.size() >= 2 ? Component.translatable("siftec.vehicle.route", route.size(), stopAt.size())
            : Component.translatable("siftec.vehicle.driving");
        driver.sendOverlayMessage(Component.translatable("siftec.vehicle.hud", getName(), fuelPercent(), state));
    }

    // ------------------------------------------------------------------------------------------------ routes

    /** V: starts recording a new route, or finishes the one being recorded. */
    public void toggleRecording(@Nullable ServerPlayer driver) {
        VehicleRecording.toggleRecording(this, driver);
    }

    // ------------------------------------------------------------------------------------------------ players

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        ItemStack held = player.getItemInHand(hand);
        if (!(player instanceof ServerPlayer server)) return InteractionResult.SUCCESS;
        // the Build Gun aimed at a vehicle works as it does anywhere: its dismantle mode takes the vehicle back
        if (held.getItem() instanceof BuildGunItem) {
            BuildGun.use(server);
            return InteractionResult.SUCCESS;
        }
        if (Vehicles.foreign(this, companyId, server)) {
            server.sendOverlayMessage(Component.translatable("siftec.vehicle.not_yours"));
            return InteractionResult.SUCCESS;
        }
        if (player.isSecondaryUseActive()) {
            VehicleCargo.openCargo(this, server);
            return InteractionResult.SUCCESS;
        }
        if (VehicleCargo.refuel(this, server, hand, held)) return InteractionResult.SUCCESS;
        if (!isVehicle()) player.startRiding(this);
        return InteractionResult.SUCCESS;
    }

    public void openCargo(ServerPlayer player) {
        VehicleCargo.openCargo(this, player);
    }

    /** Blocks a player can be from the vehicle and keep its storage open. */
    public static final double CARGO_REACH = 8.0;

    /**
     * Whether a player's open window on the storage stays open: the vehicle is still in the world, the player is alive,
     * close by, and (unless in creative) still in the company the vehicle belongs to.
     */
    public boolean mayKeepCargoOpen(Player who) {
        if (isRemoved() || !BuildingMenus.alive(who) || who.level() != level()) return false;
        if (who.distanceToSqr(this) > CARGO_REACH * CARGO_REACH) return false;
        return !(who instanceof ServerPlayer server) || !Vehicles.foreign(this, companyId, server);
    }

    // ------------------------------------------------------------------------------------------------ out of the world

    /**
     * Fallen out of the bottom of the world: not deleted as vanilla deletes things there, but put back at the dock of
     * the Truck Station it last stopped at, or else beside its company's HUB, or else at world spawn.
     */
    @Override
    public void onBelowWorld() {
        if (level() instanceof ServerLevel level && returnHome(level)) return;
        super.onBelowWorld();
    }

    /** Puts the vehicle back where {@link #onBelowWorld} says. True if it was put somewhere. */
    public boolean returnHome(ServerLevel level) {
        return VehicleRescue.returnHome(this, level);
    }

    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction callback) {
        if (!hasPassenger(passenger)) return;
        Vec3 seat = local(kind.seatX, kind.seatY + 0.5, kind.seatZ);
        Vec3 attach = passenger.getVehicleAttachmentPoint(this);
        callback.accept(passenger, seat.x - attach.x, seat.y - attach.y, seat.z - attach.z);
    }

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    // ------------------------------------------------------------------------------------------------ saving

    @Override
    protected void addAdditionalSaveData(ValueOutput view) {
        view.putString("Company", companyId);
        view.putDouble("Fuel", fuelMj);
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < cargo.getContainerSize(); i++) items.add(cargo.getItem(i));
        view.store("Cargo", ItemStack.OPTIONAL_CODEC.listOf(), items);
        view.store("Refund", ItemStack.OPTIONAL_CODEC.listOf(), new ArrayList<>(refund));
        view.store("Route", Vec3.CODEC.listOf(), new ArrayList<>(route));
        view.store("StopAt", Codec.INT.listOf(), new ArrayList<>(stopAt));
        view.store("StopStation", BlockPos.CODEC.listOf(), new ArrayList<>(stopStation));
        view.putInt("Target", target);
        if (lastStation != null) view.store("LastStation", BlockPos.CODEC, lastStation);
        view.putBoolean("Recording", recording);
        view.putInt("StopsMade", stopsMade);
        view.store("LegTicks", Codec.INT.listOf(), new ArrayList<>(legTicks));
    }

    @Override
    protected void readAdditionalSaveData(ValueInput view) {
        companyId = view.getStringOr("Company", "");
        fuelMj = view.getDoubleOr("Fuel", 0);
        List<ItemStack> items = view.read("Cargo", ItemStack.OPTIONAL_CODEC.listOf()).orElse(List.of());
        for (int i = 0; i < cargo.getContainerSize(); i++) cargo.setItem(i, i < items.size() ? items.get(i) : ItemStack.EMPTY);
        refund.clear();
        for (ItemStack stack : view.read("Refund", ItemStack.OPTIONAL_CODEC.listOf()).orElse(List.of())) if (!stack.isEmpty()) refund.add(stack);
        route.clear();
        route.addAll(view.read("Route", Vec3.CODEC.listOf()).orElse(List.of()));
        stopAt.clear();
        stopAt.addAll(view.read("StopAt", Codec.INT.listOf()).orElse(List.of()));
        stopStation.clear();
        stopStation.addAll(view.read("StopStation", BlockPos.CODEC.listOf()).orElse(List.of()));
        if (stopStation.size() != stopAt.size()) {
            stopAt.clear();
            stopStation.clear();
        }
        target = view.getIntOr("Target", 0);
        lastStation = view.read("LastStation", BlockPos.CODEC).orElse(null);
        recording = view.getBooleanOr("Recording", false);
        stopsMade = view.getIntOr("StopsMade", 0);
        legTicks.clear();
        legTicks.addAll(view.read("LegTicks", Codec.INT.listOf()).orElse(List.of()));
        syncFuel();
    }

    // ------------------------------------------------------------------------------------------------ driving by time

    /**
     * True while it drives its route by itself: a route, nobody driving or recording, no selftest pilot. Only such a
     * vehicle goes on by time while its land does not tick ({@link SimVehicles}).
     */
    public boolean autonomous() {
        return !recording && route.size() >= 2 && pilot == null && getFirstPassenger() == null;
    }

    /** Everything the time-driven vehicle needs, written into the simulation's record of it. */
    public void toSim(SimData.Vehicle v) {
        VehicleSimState.toSim(this, v);
    }

    /** Puts a vehicle the simulation drove back into the world as it left it. */
    public void fromSim(SimData.Vehicle v) {
        VehicleSimState.fromSim(this, v);
    }
}
