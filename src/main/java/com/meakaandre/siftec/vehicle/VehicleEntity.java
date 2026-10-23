package com.meakaandre.siftec.vehicle;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildGun;
import com.meakaandre.siftec.building.BuildGunItem;
import com.meakaandre.siftec.building.BuildingMenus;
import com.meakaandre.siftec.building.BuildingPower;
import com.meakaandre.siftec.building.PowerRole;
import com.meakaandre.siftec.building.TruckStations;
import com.meakaandre.siftec.claim.Claims;
import com.meakaandre.siftec.node.NodeSavedData;
import com.meakaandre.siftec.prefab.Prefabs;
import com.meakaandre.siftec.sim.Keys;
import com.meakaandre.siftec.sim.SimData;
import com.meakaandre.siftec.sim.SimVehicles;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.portal.TeleportTransition;
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
    private static final EntityDataAccessor<Float> SPEED = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> FUEL = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.INT);
    /** How long a vehicle on a route stands at a stop. */
    public static final int DOCK_TICKS = 100;
    /** Blocks between the points of a recorded route. */
    static final double POINT_SPACING = 2.0;
    /** Ticks between the driver's HUD updates, and between "no fuel" reminders. */
    private static final int HUD_INTERVAL_TICKS = 10, NO_FUEL_MESSAGE_TICKS = 40;
    /** mB in a bucket, as a fuel's megajoules are given per mB. */
    private static final int MB_PER_BUCKET = 1000;
    // ---- driving: speeds are blocks a tick, shares are of the kind's top speed
    /** Speed kept each tick while braking, and while rolling with no throttle. */
    private static final double BRAKE_DRAG = 0.7, COAST_DRAG = 0.94;
    /** Top speed in reverse, as a share of the top speed forward. */
    private static final double REVERSE_SHARE = 0.4;
    /** Below this speed it stands still. */
    private static final double STOP_SPEED = 0.003;
    /** The share of top speed at which its wheels turn it fully; slower, it turns less. */
    private static final double FULL_GRIP_SHARE = 0.3;
    /** Downward speed while on the ground; gravity and air drag a tick while falling (vanilla's). */
    private static final double GROUND_VY = -0.04, GRAVITY = 0.08, AIR_DRAG = 0.98;
    /** In water: lift a tick, the fastest it rises, and the speed it keeps a tick. */
    private static final double SWIM_LIFT = 0.06, MAX_SWIM_RISE = 0.1, WATER_DRAG = 0.9;
    /** Least change of speed that is sent to clients. */
    private static final double SPEED_SYNC_STEP = 0.005;
    /**
     * {@link #bodyBlocked}: the share of its width checked, how high over its feet the check starts (over a one-block
     * step) and ends at least, and the gap left under its roof.
     */
    private static final double BODY_CHECK_WIDTH = 0.7, STEP_CLEARANCE = 1.05, MIN_CHECK_TOP = 1.1, ROOF_GAP = 0.05;
    // ---- routes
    /** The fewest points a recorded route keeps. */
    private static final int MIN_ROUTE_POINTS = 3;
    /** Points it must have recorded since its last stop at a station before that station counts as a new stop. */
    private static final int RESTOP_POINTS = 6;
    /** Distances under this count as the same place, or as no progress. */
    private static final double SAME_SPOT = 0.01;
    /** Farther than this from its next point, it carries on from the nearest point instead. */
    private static final int OFF_ROUTE_DISTANCE = 24;
    /** How close it must come to a stop, and to any other point, to have reached it. */
    private static final double STOP_ARRIVE = 1.2, POINT_ARRIVE = 1.8;
    /** Ticks without getting closer before it gives up on a point. */
    private static final int STUCK_TICKS = 80;
    /** Degrees off its heading beyond which it slows for the turn, and how close to a stop it slows to dock. */
    private static final int SHARP_TURN_DEGREES = 45, APPROACH_DISTANCE = 6;
    /** Autopilot speeds: turning hard, coming up to a stop, cruising; and how far over its limit it goes before braking. */
    private static final double TURN_SPEED_SHARE = 0.3, APPROACH_SPEED_SHARE = 0.35, CRUISE_SPEED_SHARE = 0.8;
    private static final double BRAKE_MARGIN = 0.05;
    /** The least grip the autopilot reckons with, so its steering never divides by zero. */
    private static final double MIN_GRIP = 0.05;
    /** How high over the ground a vehicle that fell out of the world is put back. */
    private static final double RESCUE_LIFT = 0.1;

    public final VehicleKind kind;
    public final SimpleContainer cargo;
    public final List<ItemStack> refund = new ArrayList<>();
    public String companyId = "";
    /** Megajoules of fuel in its tank. */
    public double fuelMj;
    /** Blocks a tick, forward positive. */
    private double speed;

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
    private int docked, stuck;
    private double lastDist = Double.MAX_VALUE;
    /**
     * Ticks each leg of the route took the last time it drove it for real (index: the point the leg ends at), so the
     * factory simulation drives it by the same times while its land does not tick.
     */
    public final List<Integer> legTicks = new ArrayList<>();
    /** The tick it set off on the leg it is driving. */
    private int legStart = -1;
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

    /** True while it moves itself: its collision box is then only the square middle of its body (see {@link #drive}). */
    private boolean moving;

    /**
     * Its box: the whole of its turned body (its model's width and length), as the smallest box round it, so the game
     * finds it to pick, hit and interact with along all its length; picking it is then narrowed to the body itself
     * ({@code mixin.VehiclePickMixin}). While it moves itself it collides as the square middle of its body, and its
     * ends are kept out of walls ({@link #bodyClear}).
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

    /**
     * Which squares along its length (front to back) have something solid in them above the height it can drive up (one
     * block), as bits: its front and back stop at a wall as its middle does. The squares reach its very ends.
     */
    int bodyBlocked() {
        double w = kind.boxWidth, l = kind.boxLength;
        int n = squares();
        double side = w * BODY_CHECK_WIDTH / 2, from = getY() + STEP_CLEARANCE,
            to = getY() + Math.max(MIN_CHECK_TOP, kind.boxHeight - ROOF_GAP);
        double reach = Math.max(0, l / 2 - side);
        int mask = 0;
        for (int i = 0; i < n; i++) {
            double z = n == 1 ? 0 : -reach + i * (2 * reach) / (n - 1);
            Vec3 c = local(0, 0, z);
            if (!level().noCollision(this, new AABB(c.x - side, from, c.z - side, c.x + side, to, c.z + side))) mask |= 1 << i;
        }
        return mask;
    }

    /** How many squares {@link #bodyBlocked} checks along its length (the first is its back, the last its front). */
    int squares() {
        return Math.max(1, (int) Math.ceil(kind.boxLength / kind.boxWidth));
    }

    public void addFuel(double mj) {
        fuelMj = Math.min(kind.tankMj(), fuelMj + mj);
        syncFuel();
    }

    private void syncFuel() {
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
            if (recording) record(level);
            if (driver != null && tickCount % HUD_INTERVAL_TICKS == 0) hud(driver);
        } else if (!recording && route.size() >= 2) {
            double[] auto = autopilot(level);
            throttle = auto[0];
            steer = auto[1];
            brake = auto[2] > 0;
        }
        if (fuelMj <= 0 && throttle != 0) {
            throttle = 0;
            if (driver != null && tickCount % NO_FUEL_MESSAGE_TICKS == 0)
                driver.sendOverlayMessage(Component.translatable("siftec.vehicle.no_fuel"));
        }
        drive(throttle, steer, brake);
        if (throttle != 0) {
            fuelMj = Math.max(0, fuelMj - kind.mw / 20.0);
            syncFuel();
        }
    }

    /** Throttle and steering (-1 to 1 each, steering right positive) and the brake: turns, speeds up or slows, and moves. */
    void drive(double throttle, double steer, boolean brake) {
        double max = kind.maxSpeed;
        if (brake) {
            speed *= BRAKE_DRAG;
        } else if (throttle != 0) {
            speed = Mth.clamp(speed + throttle * kind.accel, -max * REVERSE_SHARE, max);
        } else {
            speed *= COAST_DRAG;
        }
        if (Math.abs(speed) < STOP_SPEED) speed = 0;
        Vec3 before = position();
        // what its body already stands in (a dock it parked in, say) does not hold it; only driving into more does
        int blockedBefore = speed == 0 ? 0 : bodyBlocked();
        if (steer != 0 && speed != 0) {
            double grip = Math.min(1, Math.abs(speed) / (max * FULL_GRIP_SHARE));
            setYRot(getYRot() + (float) (Mth.clamp(steer, -1, 1) * kind.turnDegrees * grip * Math.signum(speed)));
        }
        Vec3 f = forward();
        Vec3 v = getDeltaMovement();
        double vy = onGround() ? GROUND_VY : (v.y - GRAVITY) * AIR_DRAG;
        if (isInWater()) {
            vy = Math.min(v.y + SWIM_LIFT, MAX_SWIM_RISE);
            speed *= WATER_DRAG;
        }
        setDeltaMovement(f.x * speed, vy, f.z * speed);
        // it moves as the square middle of its body; its front and back must not end up in a wall
        moving = true;
        setBoundingBox(makeBoundingBox());
        move(MoverType.SELF, getDeltaMovement());
        moving = false;
        // the end it drives toward stops at a wall as its middle does (turning is never held back: a tail swinging
        // past a corner does not stop it)
        int lead = speed > 0 ? 1 << (squares() - 1) : 1;
        if (speed != 0 && (bodyBlocked() & ~blockedBefore & lead) != 0) {
            setPos(before.x, getY(), before.z);
            speed *= 0.5;
            horizontalCollision = true;
        }
        setBoundingBox(makeBoundingBox());
        if (horizontalCollision) speed *= 0.5;
        if (Math.abs(entityData.get(SPEED) - speed) > SPEED_SYNC_STEP) entityData.set(SPEED, (float) speed);
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
        if (!recording) {
            recording = true;
            legTicks.clear();
            route.clear();
            stopAt.clear();
            stopStation.clear();
            route.add(position());
            if (driver != null) driver.sendSystemMessage(Component.translatable("siftec.vehicle.record_start"));
            return;
        }
        recording = false;
        if (route.size() < MIN_ROUTE_POINTS) {
            route.clear();
            stopAt.clear();
            stopStation.clear();
            if (driver != null) driver.sendSystemMessage(Component.translatable("siftec.vehicle.record_short"));
            return;
        }
        target = 0;
        lastDist = Double.MAX_VALUE;
        stuck = 0;
        if (driver != null) driver.sendSystemMessage(Component.translatable("siftec.vehicle.record_done", route.size(), stopAt.size(), getName()));
    }

    /** While recording: a point every couple of blocks, and a stop at each Truck Station dock it drives through. */
    void record(ServerLevel level) {
        Vec3 here = position();
        if (here.distanceTo(route.getLast()) >= POINT_SPACING) route.add(here);
        BlockPos station = TruckStations.near(level, here, this);
        if (station == null) return;
        boolean again = !stopStation.isEmpty() && stopStation.getLast().equals(station)
            && route.size() - 1 - stopAt.getLast() < RESTOP_POINTS;
        if (again) return;
        if (here.distanceTo(route.getLast()) > SAME_SPOT) route.add(here);
        stopAt.add(route.size() - 1);
        stopStation.add(station);
        lastStation = station;
    }

    private int stopIndex(int point) {
        for (int i = 0; i < stopAt.size(); i++) if (stopAt.get(i) == point) return i;
        return -1;
    }

    /** Driving the route by itself: {throttle, steering, brake}. */
    private double[] autopilot(ServerLevel level) {
        if (target >= route.size()) target = 0;
        Vec3 goal = route.get(target);
        double dx = goal.x - getX(), dz = goal.z - getZ(), dist = Math.sqrt(dx * dx + dz * dz);
        // got out somewhere else along the way: carry on from the nearest point
        if (dist > OFF_ROUTE_DISTANCE) {
            legStart = -1;
            target = nearestPoint();
            goal = route.get(target);
            dx = goal.x - getX();
            dz = goal.z - getZ();
            dist = Math.sqrt(dx * dx + dz * dz);
        }
        int stop = stopIndex(target);
        if (dist < (stop >= 0 ? STOP_ARRIVE : POINT_ARRIVE)) {
            if (docked == 0 && legStart >= 0) {
                while (legTicks.size() < route.size()) legTicks.add(0);
                legTicks.set(target, Math.max(1, tickCount - legStart));
                legStart = -1;
            }
            if (stop >= 0 && docked < DOCK_TICKS) {
                if (docked == 0) {
                    itemsMoved += TruckStations.dock(level, stopStation.get(stop), this);
                    stopsMade++;
                    lastStation = stopStation.get(stop);
                }
                docked++;
                return new double[]{0, 0, 1};
            }
            docked = 0;
            target = (target + 1) % route.size();
            lastDist = Double.MAX_VALUE;
            stuck = 0;
            legStart = tickCount;
            return new double[]{0, 0, 0};
        }
        // not getting any closer for a while (something in the way): try the next point
        if (dist < lastDist - SAME_SPOT) {
            lastDist = dist;
            stuck = 0;
        } else if (++stuck > STUCK_TICKS) {
            target = (target + 1) % route.size();
            lastDist = Double.MAX_VALUE;
            stuck = 0;
            legStart = -1;
        }
        float want = (float) (Math.atan2(-dx, dz) * Mth.RAD_TO_DEG);
        double off = Mth.wrapDegrees(want - getYRot());
        double limit = kind.maxSpeed * (Math.abs(off) > SHARP_TURN_DEGREES ? TURN_SPEED_SHARE
            : stop >= 0 && dist < APPROACH_DISTANCE ? APPROACH_SPEED_SHARE : CRUISE_SPEED_SHARE);
        // wheels turn it only while it rolls: creep forward while turning hard rather than stopping
        double throttle = speed < limit ? 1 : 0;
        boolean brake = speed > limit + BRAKE_MARGIN;
        // drive() turns by steering x turnDegrees x grip: aim the turn at what is off, no further
        double grip = Math.max(MIN_GRIP, Math.min(1, Math.abs(speed) / (kind.maxSpeed * FULL_GRIP_SHARE)));
        double steer = Mth.clamp(off / (kind.turnDegrees * grip), -1, 1);
        return new double[]{throttle, steer, brake ? 1 : 0};
    }

    private int nearestPoint() {
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < route.size(); i++) {
            double d = route.get(i).distanceToSqr(position());
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
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
            openCargo(server);
            return InteractionResult.SUCCESS;
        }
        if (refuel(server, hand, held)) return InteractionResult.SUCCESS;
        if (!isVehicle()) player.startRiding(this);
        return InteractionResult.SUCCESS;
    }

    public void openCargo(ServerPlayer player) {
        MenuType<ChestMenu> type = switch (kind.rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new ChestMenu(type, id, inventory, cargo, kind.rows) {
            @Override
            public boolean stillValid(Player who) {
                return mayKeepCargoOpen(who);
            }
        }, getName()));
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
        ejectPassengers();
        ServerLevel to = level;
        Vec3 spot = null;
        if (lastStation != null) {
            level.getChunk(lastStation);
            var station = TruckStations.at(level, lastStation);
            if (station != null && TruckStations.serves(station, this)) spot = TruckStations.dock(station);
        }
        if (spot == null) {
            var hub = Claims.hubOf(level.getServer(), companyId);
            ServerLevel hubLevel = hub == null ? null : level.getServer().getLevel(hub.dimension());
            if (hubLevel != null) {
                to = hubLevel;
                spot = besideHub(hubLevel, hub.pos());
            }
        }
        if (spot == null) {
            to = level.getServer().overworld();
            var origin = NodeSavedData.get(level.getServer());
            int x = origin.hasOrigin() ? origin.originX() : 0, z = origin.hasOrigin() ? origin.originZ() : 0;
            to.getChunk(new BlockPos(x, 0, z));
            spot = new Vec3(x + 0.5, to.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z + 0.5);
        }
        speed = 0;
        docked = 0;
        lastDist = Double.MAX_VALUE;
        stuck = 0;
        setDeltaMovement(Vec3.ZERO);
        resetFallDistance();
        if (to == level) {
            setPos(spot.x, spot.y + RESCUE_LIFT, spot.z);
        } else if (teleport(new TeleportTransition(to, new Vec3(spot.x, spot.y + RESCUE_LIFT, spot.z), Vec3.ZERO, getYRot(),
            getXRot(), TeleportTransition.DO_NOTHING)) == null) {
            return false;
        }
        Siftec.LOGGER.info("SIFTEC: {} fell out of the world; put back at {} {}", kind.id, to.dimension().identifier(), BlockPos.containing(spot).toShortString());
        return true;
    }

    /** A spot on the ground just outside the HUB that stands at this terminal (on the ground by the terminal if none is found). */
    private static Vec3 besideHub(ServerLevel level, BlockPos terminal) {
        level.getChunk(terminal);
        int x = terminal.getX(), z = terminal.getZ();
        var hub = Prefabs.hubAt(level, terminal);
        if (hub != null && hub.anchor != null && hub.facing != null) {
            var box = Prefabs.bounds(new Prefabs.Spot(Prefabs.Kind.HUB, hub.anchor, hub.facing));
            x = (int) Math.floor(box.maxX) + 2;
            z = (int) Math.floor((box.minZ + box.maxZ) / 2);
            level.getChunk(new BlockPos(x, 0, z));
        }
        return new Vec3(x + 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z + 0.5);
    }

    /** A bucket of a fuel the Fuel Generator burns goes into the tank, and the empty bucket comes back. */
    private boolean refuel(ServerPlayer player, InteractionHand hand, ItemStack held) {
        if (held.isEmpty()) return false;
        for (String id : BuildingPower.fluidFuels(PowerRole.FUEL)) {
            Fluid fluid = BuiltInRegistries.FLUID.getValue(Identifier.parse(id));
            if (fluid.getBucket() == Items.AIR || !held.is(fluid.getBucket())) continue;
            double mj = BuildingPower.fluidMj(PowerRole.FUEL, fluid) * MB_PER_BUCKET;
            if (fuelMj + mj > kind.tankMj() + 1) {
                player.sendOverlayMessage(Component.translatable("siftec.vehicle.full", getName()));
                return true;
            }
            addFuel(mj);
            if (!player.hasInfiniteMaterials()) player.setItemInHand(hand, ItemUtils.createFilledResult(held, player, new ItemStack(Items.BUCKET)));
            player.sendOverlayMessage(Component.translatable("siftec.vehicle.fuelled", getName(), fuelPercent()));
            return true;
        }
        return false;
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
        v.uuid = getUUID().toString();
        v.type = kind.id;
        v.dim = level().dimension().identifier().toString();
        v.company = companyId;
        v.fuel = fuelMj;
        v.speed = kind.maxSpeed;
        v.mw = kind.mw;
        v.tank = kind.tankMj();
        v.slots = kind.slots();
        v.cargo.clear();
        for (int i = 0; i < cargo.getContainerSize(); i++) {
            ItemStack s = cargo.getItem(i);
            if (!s.isEmpty()) v.cargo.add(new SimData.Slot(Keys.of(s), s.getCount()));
        }
        v.refund.clear();
        for (ItemStack s : refund) if (!s.isEmpty()) v.refund.add(new SimData.Slot(Keys.of(s), s.getCount()));
        v.route.clear();
        for (Vec3 p : route) {
            v.route.add(p.x);
            v.route.add(p.y);
            v.route.add(p.z);
        }
        v.stopAt.clear();
        v.stopAt.addAll(stopAt);
        v.stopStation.clear();
        for (BlockPos p : stopStation) v.stopStation.add(p.asLong());
        v.legTicks.clear();
        v.legTicks.addAll(legTicks);
        v.target = Math.max(0, Math.min(target, route.size() - 1));
        v.lastStation = lastStation == null ? null : lastStation.asLong();
        v.stopsMade = stopsMade;
        v.itemsMoved = itemsMoved;
        v.docked = docked > 0 ? docked : -1;
        v.x = getX();
        v.y = getY();
        v.z = getZ();
        v.yaw = getYRot();
        // how far along its leg it is, by distance
        int n = route.size();
        Vec3 to = route.get(v.target), from = route.get((v.target - 1 + n) % n);
        double leg = from.distanceTo(to), left = position().distanceTo(to);
        v.leg = leg <= 0 ? 0 : Math.max(0, Math.min(1, 1 - left / leg)) * SimVehicles.legTicks(v, v.target);
    }

    /** Puts a vehicle the simulation drove back into the world as it left it. */
    public void fromSim(SimData.Vehicle v) {
        companyId = v.company;
        fuelMj = v.fuel;
        for (int i = 0; i < cargo.getContainerSize(); i++) cargo.setItem(i, ItemStack.EMPTY);
        int slot = 0;
        for (var s : v.cargo) {
            ItemStack stack = Keys.stack(s.key, s.count);
            while (!stack.isEmpty() && slot < cargo.getContainerSize()) {
                ItemStack part = stack.split(stack.getMaxStackSize());
                cargo.setItem(slot++, part);
            }
        }
        refund.clear();
        for (var s : v.refund) {
            ItemStack stack = Keys.stack(s.key, s.count);
            if (!stack.isEmpty()) refund.add(stack);
        }
        route.clear();
        for (int i = 0; i + 2 < v.route.size(); i += 3) route.add(new Vec3(v.route.get(i), v.route.get(i + 1), v.route.get(i + 2)));
        stopAt.clear();
        stopAt.addAll(v.stopAt);
        stopStation.clear();
        for (long p : v.stopStation) stopStation.add(BlockPos.of(p));
        legTicks.clear();
        legTicks.addAll(v.legTicks);
        target = route.isEmpty() ? 0 : Math.max(0, Math.min(v.target, route.size() - 1));
        lastStation = v.lastStation == null ? null : BlockPos.of(v.lastStation);
        stopsMade = v.stopsMade;
        itemsMoved = v.itemsMoved;
        recording = false;
        // standing at a stop it already loaded or unloaded at: it waits out the rest there without doing it again
        docked = v.docked >= 0 ? Math.max(1, v.docked) : 0;
        lastDist = Double.MAX_VALUE;
        stuck = 0;
        legStart = -1;
        speed = 0;
        syncFuel();
    }
}
