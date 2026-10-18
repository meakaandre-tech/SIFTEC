package com.meakaandre.siftec.vehicle;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildGun;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.BuildingLogic;
import com.meakaandre.siftec.building.BuildingTemplate;
import com.meakaandre.siftec.building.BuildingTests;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.Category;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.Placement;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Selftests for work package 14, vehicles, run by the smoke test: {@code /siftec selftest vehicles}. The building list
 * has the Tractor, Truck, Explorer and Truck Station; the Build Gun puts a vehicle down for its cost (refusing it locked,
 * unpaid or with no room) and takes it back with a full refund. Then the done-when: two Truck Stations, one loading and
 * one unloading; a Truck records a loop through both docks with a pretend driver, and then drives it by itself,
 * carrying the first station's load to the second and filling its tank at the first. {@code restart} checks the Truck
 * and its route came back after a restart and that it is still driving. Every check prints "SELFTEST vehicles".
 */
public final class VehicleTests {
    private static final int X = 4800, Y = 230, Z = 3200, GAP = 26;
    private static final int LOAD = 64;
    private static final String COMPANY = "selftest_vehicles";

    private record Later(long at, Runnable run) {
    }

    private static final List<Later> LATER = new ArrayList<>();
    private static final List<Runnable> EVERY_TICK = new ArrayList<>();
    private static int passed, failed;

    private VehicleTests() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (Runnable r : new ArrayList<>(EVERY_TICK)) r.run();
            long now = server.overworld().getGameTime();
            for (Later l : new ArrayList<>(LATER)) {
                if (l.at > now) continue;
                LATER.remove(l);
                l.run.run();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LATER.clear();
            EVERY_TICK.clear();
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("vehicles").executes(VehicleTests::run)
                    .then(Commands.literal("restart").executes(VehicleTests::restart))))
                .then(Commands.literal("worldtest").then(Commands.literal("vehicles").then(Commands.literal("view").executes(VehicleTests::view))))));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static void later(ServerLevel level, int ticks, Runnable run) {
        LATER.add(new Later(level.getGameTime() + ticks, run));
    }

    private static void report(CommandSourceStack source, String text) {
        String line = "SELFTEST vehicles " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        report(source, (ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    private static Company company(ServerLevel level, String id, String... done) {
        Company c = new Company();
        c.id = id;
        c.name = id;
        c.done.addAll(List.of(done));
        CompanyData.get(level.getServer()).companies().put(id, c);
        return c;
    }

    private static Block create(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("create", id));
    }

    private static long count(ServerLevel level, @Nullable ControllerBlockEntity c, Item item) {
        long n = 0;
        if (c == null) return -1;
        for (BlockPos pos : BuildingLogic.cells(c, create("item_vault"))) {
            Storage<ItemVariant> s = ItemStorage.SIDED.find(level, pos, null);
            if (s == null) continue;
            for (StorageView<ItemVariant> v : s) if (v.getResource().getItem() == item) n += v.getAmount();
            // the two vaults are one connected vault: count it once
            break;
        }
        return n;
    }

    private static int cargo(VehicleEntity v, Item item) {
        int n = 0;
        for (int i = 0; i < v.cargo.getContainerSize(); i++) if (v.cargo.getItem(i).is(item)) n += v.cargo.getItem(i).getCount();
        return n;
    }

    private static AABB site() {
        return new AABB(X - 30, Y - 2, Z - 10, X + GAP + 30, Y + 12, Z + 32);
    }

    private static @Nullable VehicleEntity truck(ServerLevel level) {
        for (VehicleEntity v : level.getEntitiesOfClass(VehicleEntity.class, site())) if (v.kind == VehicleKind.TRUCK) return v;
        return null;
    }

    // ------------------------------------------------------------------------------------------------ the test

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        passed = failed = 0;
        BuildingTests.platform(level, X - 30, Z - 10, X + GAP + 30, Z + 32, Y, 12);
        for (VehicleEntity v : level.getEntitiesOfClass(VehicleEntity.class, site())) v.discard();
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, site())) e.discard();
        later(level, 20, () -> {
            listed(source);
            gun(source, level);
            route(source, level);
        });
        later(level, 4400, () -> report(source, "done: " + passed + " passed, " + failed + " failed"));
        report(source, "test site ready; the test runs in a second and reports in about three minutes");
        return 1;
    }

    /** The building list has the three vehicles and the Truck Station, at Satisfactory's unlocks, in the Logistics tab. */
    private static void listed(CommandSourceStack source) {
        String[][] want = {{"tractor", "vehicular_transport"}, {"truck", "logistics_mk4"}, {"explorer", ""}};
        for (String[] w : want) {
            BuildingDef def = Buildings.def(w[0]);
            boolean unlock = def != null && (w[1].isEmpty() ? Milestones.get(def.unlock()) != null : def.unlock().equals(w[1]));
            expect(source, w[0] + " is a Build Gun vehicle in the Logistics tab with its unlock", def != null && def.vehicle() && !def.structure()
                    && def.category() == Category.LOGISTICS && unlock && Vehicles.kind(def) != null && !def.cost().isEmpty(),
                def == null ? "missing" : def.category() + " " + def.unlock() + " " + def.prefab());
        }
        BuildingDef station = Buildings.def("truck_station");
        expect(source, "the Truck Station is a building with a template, unlocked by Vehicular Transport",
            station != null && !station.vehicle() && station.logic().equals("truck_station") && station.unlock().equals("vehicular_transport")
                && BuildingTemplate.of(station) != null, station == null ? "missing" : BuildingTemplate.problem("truck_station"));
        for (VehicleKind kind : VehicleKind.values()) {
            expect(source, kind.id + " has an entity type", Vehicles.type(kind) != null
                && BuiltInRegistries.ENTITY_TYPE.getKey(Vehicles.type(kind)).equals(Siftec.id(kind.id)), "");
        }
    }

    /** The Build Gun's side: locked, unpaid and blocked are refused; paid, it goes down, and taken back, the cost returns. */
    private static void gun(CommandSourceStack source, ServerLevel level) {
        BuildingDef def = Buildings.def("tractor");
        if (def == null) return;
        Company fresh = company(level, "selftest_vehicles_fresh");
        Company able = company(level, "selftest_vehicles_able", "vehicular_transport");
        Placement p = new Placement(def, new BlockPos(X - 20, Y, Z + 26), Direction.EAST, false);
        expect(source, "gun: a company without Vehicular Transport cannot put a Tractor down",
            "siftec.build_gun.locked".equals(Vehicles.check(level, p, fresh, null, false).reason), String.valueOf(Vehicles.check(level, p, fresh, null, false).reason));
        SimpleContainer empty = new SimpleContainer(27);
        expect(source, "gun: unpaid, it says what is missing", !Vehicles.check(level, p, able, empty, false).missing.isEmpty(), "");
        BlockPos rock = BlockPos.containing(Vehicles.spot(p, VehicleKind.TRACTOR)).above();
        level.setBlock(rock, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        expect(source, "gun: no room, it is refused", "siftec.vehicle.blocked".equals(Vehicles.check(level, p, able, null, true).reason),
            String.valueOf(Vehicles.check(level, p, able, null, true).reason));
        level.setBlock(rock, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        SimpleContainer wallet = new SimpleContainer(27);
        int costTotal = 0;
        for (Milestone.Cost cost : def.cost()) {
            wallet.addItem(cost.icon().copyWithCount(cost.count()));
            costTotal += cost.count();
        }
        Buildings.Check check = Vehicles.place(level, p, able, wallet, false);
        VehicleEntity tractor = null;
        for (VehicleEntity v : level.getEntitiesOfClass(VehicleEntity.class, new AABB(p.anchor()).inflate(6))) if (v.kind == VehicleKind.TRACTOR) tractor = v;
        expect(source, "gun: paid, the Tractor goes down as one entity and the cost is taken", check.ok() && tractor != null && wallet.isEmpty()
            && tractor.companyId.equals(able.id), check.message().getString());
        if (tractor == null) return;
        tractor.cargo.setItem(0, new ItemStack(Items.COBBLESTONE, 5));
        List<ItemStack> back = Vehicles.dismantle(tractor);
        int refunded = 0, cobble = 0;
        for (ItemStack s : back) {
            if (s.is(Items.COBBLESTONE)) cobble += s.getCount();
            else refunded += s.getCount();
        }
        expect(source, "gun: taken back, its whole cost and its cargo come back", refunded == costTotal && cobble == 5 && tractor.isRemoved(),
            refunded + " of " + costTotal + ", cobblestone " + cobble);
    }

    /**
     * The done-when: a Truck records a loop through two stations' docks and then drives it unattended, unloading the
     * first station's iron at the second and filling up with the first station's fuel.
     */
    private static void route(CommandSourceStack source, ServerLevel level) {
        BuildingDef stationDef = Buildings.def("truck_station"), truckDef = Buildings.def("truck");
        if (stationDef == null || truckDef == null) return;
        Company company = company(level, COMPANY, "vehicular_transport", "logistics_mk4");
        Placement pa = new Placement(stationDef, new BlockPos(X, Y, Z), Direction.NORTH, false);
        Placement pb = new Placement(stationDef, new BlockPos(X + GAP, Y, Z), Direction.NORTH, false);
        Buildings.Check ca = Buildings.place(level, pa, company, null, true), cb = Buildings.place(level, pb, company, null, true);
        ControllerBlockEntity a = BuildGun.controllerAt(level, pa.at(0, 1, 1)), b = BuildGun.controllerAt(level, pb.at(0, 1, 1));
        expect(source, "two Truck Stations go down", ca.ok() && cb.ok() && a != null && b != null, ca.message().getString() + " / " + cb.message().getString());
        if (a == null || b == null) return;
        a.unload = false;
        b.unload = true;
        // the first station has iron to load and fuel in its tank
        for (BlockPos pos : BuildingLogic.cells(a, create("item_vault"))) {
            Storage<ItemVariant> s = ItemStorage.SIDED.find(level, pos, null);
            if (s == null) continue;
            try (Transaction tx = Transaction.openOuter()) {
                s.insert(ItemVariant.of(Items.IRON_INGOT), LOAD, tx);
                tx.commit();
            }
            break;
        }
        Fluid turbofuel = BuiltInRegistries.FLUID.getValue(Siftec.id("turbofuel"));
        BlockPos tank = BuildingLogic.cell(a, create("fluid_tank"));
        if (tank != null) {
            Storage<FluidVariant> s = FluidStorage.SIDED.find(level, tank, null);
            if (s != null) {
                try (Transaction tx = Transaction.openOuter()) {
                    s.insert(FluidVariant.of(turbofuel), FluidConstants.BUCKET, tx);
                    tx.commit();
                }
            }
        }
        expect(source, "the loading station holds the load", count(level, a, Items.IRON_INGOT) == LOAD, String.valueOf(count(level, a, Items.IRON_INGOT)));
        VehicleEntity truck = Vehicles.spawn(level, new Placement(truckDef, new BlockPos(X - 9, Y, Z + 3), Direction.EAST, false), company, List.of());
        if (truck == null) {
            expect(source, "a Truck goes down", false, "");
            return;
        }
        truck.fuelMj = 10_000;
        double startFuel = truck.fuelMj;
        Vec3 start = truck.position();
        // the pretend driver's loop: east along the docks, round, back west behind them, and round to where it started
        Vec3[] corners = {new Vec3(X + GAP + 10, Y, Z + 3.5), new Vec3(X + GAP + 10, Y, Z + 16.5), new Vec3(X - 16, Y, Z + 16.5), new Vec3(X - 16, Y, Z + 3.5)};
        truck.toggleRecording(null);
        int[] phase = {0}, corner = {0};
        long[] began = {level.getGameTime()}, recorded = {0};
        EVERY_TICK.add(new Runnable() {
            @Override
            public void run() {
                long now = level.getGameTime();
                if (truck.isRemoved()) {
                    expect(source, "the Truck stays in the world", false, "it was removed");
                    EVERY_TICK.remove(this);
                    return;
                }
                if (phase[0] == 0) {
                    Vec3 here = truck.position();
                    if (corner[0] < corners.length && Math.hypot(corners[corner[0]].x - here.x, corners[corner[0]].z - here.z) < 3) corner[0]++;
                    Vec3 aim = corner[0] < corners.length ? corners[corner[0]] : new Vec3(start.x + 20, Y, start.z);
                    if (corner[0] >= corners.length && here.x >= start.x - 0.5) {
                        // back at the start, heading the way it set off: finish the recording and get out
                        truck.pilot = null;
                        truck.toggleRecording(null);
                        recorded[0] = now;
                        boolean stops = truck.stopStation.size() == 2 && truck.stopStation.get(0).equals(a.getBlockPos()) && truck.stopStation.get(1).equals(b.getBlockPos());
                        expect(source, "the Truck recorded a route through both stations' docks, in order", !truck.recording && truck.route.size() >= 20 && stops,
                            truck.route.size() + " points, stops " + truck.stopStation + " (stations " + a.getBlockPos().toShortString() + ", " + b.getBlockPos().toShortString() + ")");
                        phase[0] = 1;
                        return;
                    }
                    double off = Mth.wrapDegrees((float) (Math.atan2(-(aim.x - here.x), aim.z - here.z) * Mth.RAD_TO_DEG) - truck.getYRot());
                    double limit = Math.abs(off) > 30 ? 0.15 : 0.28;
                    truck.pilot = new VehicleInputPayload(truck.syncedSpeed() < limit, false, off < -3, off > 3, false);
                    if (now - began[0] > 2400) {
                        expect(source, "the pretend driver gets round the loop", false, "stuck at " + here + " heading for corner " + corner[0]);
                        truck.pilot = null;
                        EVERY_TICK.remove(this);
                    }
                    return;
                }
                // driving by itself: done once it has made both stops and come back round to its first point
                boolean home = truck.stopsMade >= 2 && truck.position().distanceTo(truck.route.getFirst()) < 3;
                if (home) {
                    long atA = count(level, a, Items.IRON_INGOT), atB = count(level, b, Items.IRON_INGOT);
                    expect(source, "unattended, the Truck stopped at both stations", truck.stopsMade >= 2, truck.stopsMade + " stops");
                    expect(source, "unattended, it carried the load from the loading station to the unloading one",
                        atA == 0 && atB == LOAD && cargo(truck, Items.IRON_INGOT) == 0, "loader " + atA + ", unloader " + atB + ", on board " + cargo(truck, Items.IRON_INGOT));
                    expect(source, "it filled its tank from the loading station's fuel", truck.fuelMj > startFuel + 30_000
                            && tank != null && BuiltInRegistries.FLUID.getKey(TankFluid.at(level, tank)).getPath().equals("empty"),
                        Math.round(truck.fuelMj) + " MJ, tank " + (tank == null ? "missing" : BuiltInRegistries.FLUID.getKey(TankFluid.at(level, tank))));
                    expect(source, "a lap by itself took " + (now - recorded[0]) + " ticks", true, "");
                    EVERY_TICK.remove(this);
                    return;
                }
                if (now - recorded[0] > 3000) {
                    expect(source, "unattended, the Truck drives its route round to its first point", false, "at " + truck.position() + ", point " + truck.target
                        + " of " + truck.route.size() + ", stops made " + truck.stopsMade + ", fuel " + Math.round(truck.fuelMj));
                    EVERY_TICK.remove(this);
                }
            }
        });
    }

    /** After a restart: the Truck, its route and the stations' modes came back, and it is still driving itself. */
    private static int restart(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        passed = failed = 0;
        int[] tries = {0};
        Runnable[] look = new Runnable[1];
        look[0] = () -> {
            VehicleEntity truck = truck(level);
            if (truck == null) {
                if (++tries[0] < 20) {
                    later(level, 10, look[0]);
                } else {
                    expect(source, "restart: the Truck is back", false, "none in the test site");
                }
                return;
            }
            ControllerBlockEntity b = BuildGun.controllerAt(level, new Placement(Buildings.def("truck_station"), new BlockPos(X + GAP, Y, Z), Direction.NORTH, false).at(0, 1, 1));
            expect(source, "restart: the Truck, its company, its route and its stops are back", truck.companyId.equals(COMPANY) && truck.route.size() >= 20
                && truck.stopStation.size() == 2, truck.companyId + ", " + truck.route.size() + " points, " + truck.stopStation.size() + " stops");
            expect(source, "restart: the unloading station still unloads", b != null && b.unload, "");
            // the smoke run's other tests (ten in-game minutes of always-running factories) may have run its tank dry
            truck.addFuel(truck.kind.tankMj());
            Vec3 was = truck.position();
            // longer than a stop at a dock (DOCK_TICKS), so a Truck that came back standing at one has driven on by then
            later(level, VehicleEntity.DOCK_TICKS + 140, () -> {
                expect(source, "restart: it is still driving itself", !truck.isRemoved() && truck.position().distanceTo(was) > 2,
                    "moved " + String.format("%.1f", truck.position().distanceTo(was)));
                report(source, "restart done: " + passed + " passed, " + failed + " failed");
            });
        };
        later(level, 10, look[0]);
        return 1;
    }

    /** For the client's screenshot: one of each vehicle in a row, the camera in front of them. */
    private static int view(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        int vx = X + GAP + 60;
        BuildingTests.platform(level, vx - 10, Z - 10, vx + 30, Z + 20, Y, 8);
        for (VehicleEntity v : level.getEntitiesOfClass(VehicleEntity.class, new AABB(vx - 10, Y - 2, Z - 10, vx + 30, Y + 8, Z + 20))) v.discard();
        int x = vx;
        for (VehicleKind kind : VehicleKind.values()) {
            BuildingDef def = Buildings.def(kind.id);
            if (def == null) continue;
            Placement p = new Placement(def, new BlockPos(x, Y, Z + 4), Direction.WEST, false);
            Vehicles.spawn(level, p, null, List.of());
            x += 7;
        }
        var players = source.getServer().getPlayerList().getPlayers();
        if (!players.isEmpty()) {
            ServerPlayer player = players.get(0);
            player.teleportTo(level, vx + 6.5, Y + 4, Z + 14.5, java.util.Set.of(), 180f, 18f, true);
        }
        report(source, "view: one of each vehicle at " + vx + " " + Y + " " + Z);
        return 1;
    }

    /** The fluid in a tank. */
    private static final class TankFluid {
        static Fluid at(ServerLevel level, BlockPos tank) {
            Storage<FluidVariant> s = FluidStorage.SIDED.find(level, tank, null);
            if (s == null) return net.minecraft.world.level.material.Fluids.EMPTY;
            for (StorageView<FluidVariant> v : s) if (!v.isResourceBlank() && v.getAmount() > 0) return v.getResource().getFluid();
            return net.minecraft.world.level.material.Fluids.EMPTY;
        }
    }
}
