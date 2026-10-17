package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildingLogic;
import com.meakaandre.siftec.building.BuildingTests;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.TruckStations;
import com.meakaandre.siftec.node.NodeLook;
import com.meakaandre.siftec.node.NodeSites;
import com.meakaandre.siftec.node.NodeType;
import com.meakaandre.siftec.node.Purity;
import com.meakaandre.siftec.power.PowerTests;
import com.meakaandre.siftec.vehicle.VehicleEntity;
import com.meakaandre.siftec.vehicle.VehicleKind;
import com.meakaandre.siftec.vehicle.Vehicles;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.content.kinetics.belt.item.BeltConnectorItem;
import com.zurrtum.create.content.processing.basin.BasinBlockEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Work package 17's done-when: the same small factory built twice (a Miner Mk.1 on a normal iron node, a Smelter, a
 * Constructor making Iron Rods, its rods carried by a Truck from one Truck Station to another, into a Storage Container;
 * a Biomass Burner powering it), one left loaded and the other let go so its chunks unload. After ten in-game minutes the
 * two must hold the same rods (within one craft), with no chunk of the second loaded meanwhile, and they must still match
 * after a restart (with the second still unloaded, and again once it is loaded and caught up). Also: 200 simulated
 * buildings cost less than 1 ms a tick. Every check prints "SELFTEST always"; failures say FAIL.
 */
public final class AlwaysTests {
    /** Twin A's origin; twin B is the same, {@link #B_OFFSET} blocks south. */
    private static final int OX = -9000, OZ = -9000, Y = 150, B_OFFSET = 600;
    /** Ticks for the Biomass Burners to light before the miners go down, and the ten minutes. */
    private static final int WARMUP = 300, RUN = 12_000;
    private static final String ROD = "siftec:iron_rod", COMPANY = "selftest_always";

    private record Later(long at, Runnable run) {
    }

    private static final List<Later> LATER = new ArrayList<>();
    private static int passed, failed;
    /** Chunk loads in twin B's area while it is supposed to be unloaded. */
    private static int bLoads;
    private static boolean watchB;
    private static @Nullable UUID truckA, truckB;

    private AlwaysTests() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = server.overworld().getGameTime();
            for (Later l : new ArrayList<>(LATER)) {
                if (l.at > now) continue;
                LATER.remove(l);
                l.run.run();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LATER.clear();
            watchB = false;
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            if (!watchB || level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return;
            if (inB(chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ())) bLoads++;
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("always")
                    .then(Commands.literal("setup").executes(AlwaysTests::setup))
                    .then(Commands.literal("restart").executes(AlwaysTests::restart))
                    .then(Commands.literal("bench").executes(c -> {
                        bench(c.getSource());
                        return 1;
                    }))))));
    }

    private static void later(MinecraftServer server, int ticks, Runnable run) {
        LATER.add(new Later(server.overworld().getGameTime() + ticks, run));
    }

    private static void report(CommandSourceStack source, String text) {
        String line = "SELFTEST always " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        report(source, (ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
    }

    /** The twins' company: every milestone done, so its machines run every standard recipe. */
    private static com.meakaandre.siftec.company.Company company(MinecraftServer server) {
        var data = com.meakaandre.siftec.company.CompanyData.get(server);
        com.meakaandre.siftec.company.Company c = data.companies().get(COMPANY);
        if (c == null) {
            c = new com.meakaandre.siftec.company.Company();
            c.id = COMPANY;
            c.name = "Selftest Always Running";
            data.companies().put(COMPANY, c);
        }
        for (var m : com.meakaandre.siftec.hub.Milestones.all()) c.done.add(m.id());
        for (var m : com.meakaandre.siftec.hub.Milestones.phases()) c.done.add(m.id());
        com.meakaandre.siftec.company.Companies.save(server);
        return c;
    }

    /** A building put down for the company as in creative (no cost), or null (said why). */
    private static @Nullable ControllerBlockEntity put(ServerLevel level, CommandSourceStack source, String id, BlockPos anchor, Direction facing) {
        var def = com.meakaandre.siftec.building.Buildings.def(id);
        if (def == null) return null;
        var p = new com.meakaandre.siftec.building.Placement(def, anchor, facing, false);
        var check = com.meakaandre.siftec.building.Buildings.place(level, p, company(level.getServer()), null, true);
        if (!check.ok()) {
            report(source, "could not place " + id + " at " + anchor.toShortString() + ": " + check.message().getString()
                + (check.blocked.isEmpty() ? "" : " (first blocked " + check.blocked.getFirst().toShortString() + " " + level.getBlockState(check.blocked.getFirst()) + ")"));
            return null;
        }
        var t = com.meakaandre.siftec.building.BuildingTemplate.of(def);
        return t != null && level.getBlockEntity(p.at(t.controller)) instanceof ControllerBlockEntity c ? c : null;
    }

    // ------------------------------------------------------------------------------------------------ the twins

    /** The places of one twin, from its origin. */
    private record Twin(int ox, int oz) {
        int z0() {
            return oz - 2;
        }

        BlockPos stationL() {
            return new BlockPos(ox, Y, oz);
        }

        BlockPos stationU() {
            return new BlockPos(ox - 20, Y, oz);
        }

        BlockPos constructor() {
            return new BlockPos(ox + 7, Y + 1, z0());
        }

        BlockPos smelter() {
            return new BlockPos(ox + 20, Y + 1, z0());
        }

        BlockPos miner() {
            return new BlockPos(ox + 33, Y + 1, z0());
        }

        BlockPos node() {
            return new BlockPos(ox + 35, Y + 1, z0());
        }

        BlockPos storage() {
            return new BlockPos(ox - 31, Y + 1, oz - 3);
        }

        BlockPos burner() {
            return new BlockPos(ox + 20, Y + 1, oz - 16);
        }

        BlockPos pole() {
            return new BlockPos(ox + 14, Y + 1, oz - 9);
        }

        /** The belts, from where they start to where they end (moving west). */
        List<BlockPos[]> belts() {
            int z = z0();
            return List.of(new BlockPos[]{new BlockPos(ox + 32, Y + 1, z), new BlockPos(ox + 29, Y + 1, z)},
                new BlockPos[]{new BlockPos(ox + 19, Y + 1, z), new BlockPos(ox + 16, Y + 1, z)},
                new BlockPos[]{new BlockPos(ox + 6, Y + 1, z), new BlockPos(ox + 3, Y + 1, z)},
                new BlockPos[]{new BlockPos(ox - 23, Y + 1, oz - 3), new BlockPos(ox - 26, Y + 1, oz - 3)});
        }

        int x0() {
            return ox - 42;
        }

        int x1() {
            return ox + 44;
        }

        int z0Area() {
            return oz - 24;
        }

        int z1Area() {
            return oz + 22;
        }

        boolean holds(int x, int z) {
            return x >= x0() - 16 && x <= x1() + 16 && z >= z0Area() - 16 && z <= z1Area() + 16;
        }

        /** The truck's loop: west along the docks, round to the south and back east, and up to where it started. */
        List<Vec3> route() {
            List<Vec3> pts = new ArrayList<>();
            double road = oz + 3.5, back = oz + 16.5, east = ox + 16.5, west = ox - 36.5;
            for (double x = east; x >= west; x -= 2) pts.add(new Vec3(x, Y, road));
            for (double z = road + 2; z <= back; z += 2) pts.add(new Vec3(west, Y, z));
            for (double x = west + 2; x <= east; x += 2) pts.add(new Vec3(x, Y, back));
            for (double z = back - 2; z > road + 1; z -= 2) pts.add(new Vec3(east, Y, z));
            return pts;
        }
    }

    private static final Twin A = new Twin(OX, OZ), B = new Twin(OX, OZ + B_OFFSET);

    private static boolean inB(int x, int z) {
        return B.holds(x, z);
    }

    private static int setup(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ServerLevel level = server.overworld();
        passed = failed = 0;
        bLoads = 0;
        watchB = false;
        for (Twin t : List.of(A, B)) build(level, source, t);
        report(source, "twins built at " + A.stationL().toShortString() + " and " + B.stationL().toShortString() + "; the burners light, then the miners go down");
        later(server, WARMUP, () -> start(source));
        return 1;
    }

    private static void build(ServerLevel level, CommandSourceStack source, Twin t) {
        BuildingTests.platform(level, t.x0(), t.z0Area(), t.x1(), t.z1Area(), Y, 14);
        // the buildings stand a block higher, on stone, so their ports meet the stations' ports at one height
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = t.ox + 3; x <= t.ox + 42; x++) for (int z = t.oz - 24; z <= t.oz + 1; z++) level.setBlock(pos.set(x, Y, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        for (int x = t.ox - 33; x <= t.ox - 23; x++) for (int z = t.oz - 6; z <= t.oz - 1; z++) level.setBlock(pos.set(x, Y, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        // the iron node the miner goes on
        NodeLook look = NodeLook.of(NodeType.IRON);
        level.setBlock(t.node().below(), look.patchStone(), Block.UPDATE_ALL);
        NodeSites.put(level, t.node(), look, Purity.NORMAL);
        ControllerBlockEntity l = put(level, source, "truck_station", t.stationL(), Direction.NORTH);
        ControllerBlockEntity u = put(level, source, "truck_station", t.stationU(), Direction.NORTH);
        ControllerBlockEntity con = put(level, source, "constructor", t.constructor(), Direction.EAST);
        ControllerBlockEntity sm = put(level, source, "smelter", t.smelter(), Direction.EAST);
        ControllerBlockEntity st = put(level, source, "storage_container", t.storage(), Direction.EAST);
        ControllerBlockEntity burner = put(level, source, "biomass_burner", t.burner(), Direction.EAST);
        ControllerBlockEntity pole = put(level, source, "power_pole_mk3", t.pole(), Direction.EAST);
        if (l != null) l.unload = false;
        if (u != null) u.unload = true;
        if (con != null) BuildingLogic.setFilter(level, con, new ItemStack(item(ROD)));
        if (burner != null) burner.input.setItem(0, new ItemStack(item("siftec:solid_biofuel"), 64));
        for (BlockPos[] b : t.belts()) belt(level, b[0], b[1]);
        var pc = PowerTests.connector(level, pole);
        int wired = 0;
        for (ControllerBlockEntity c : new ControllerBlockEntity[]{burner, sm, con}) if (PowerTests.wire(PowerTests.connector(level, c), pc)) wired++;
        expect(source, (t == A ? "twin A" : "twin B") + ": stations, Constructor, Smelter, storage, Biomass Burner and pole go down and are wired",
            l != null && u != null && con != null && sm != null && st != null && burner != null && pole != null && wired == 3, wired + " of 3 wired");
        // the truck, on its loop through both docks, with a full tank
        VehicleEntity truck = new VehicleEntity(Vehicles.type(VehicleKind.TRUCK), level);
        List<Vec3> route = t.route();
        Vec3 start = route.getFirst();
        truck.snapTo(start.x, start.y, start.z, Direction.WEST.toYRot(), 0);
        truck.fuelMj = VehicleKind.TRUCK.tankMj();
        truck.companyId = COMPANY;
        truck.route.addAll(route);
        if (l != null && u != null) {
            Vec3 dl = TruckStations.dock(l), du = TruckStations.dock(u);
            addStop(truck, dl, l.getBlockPos());
            addStop(truck, du, u.getBlockPos());
        }
        level.addFreshEntity(truck);
        if (t == A) truckA = truck.getUUID();
        else truckB = truck.getUUID();
    }

    /** Makes the route point nearest a dock that dock exactly, and a stop at that station. */
    private static void addStop(VehicleEntity truck, @Nullable Vec3 dock, BlockPos station) {
        if (dock == null) return;
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < truck.route.size(); i++) {
            double d = truck.route.get(i).distanceToSqr(dock);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        truck.route.set(best, new Vec3(dock.x, dock.y, dock.z));
        truck.stopAt.add(best);
        truck.stopStation.add(station);
        // stops in route order
        List<Integer> order = new ArrayList<>(truck.stopAt);
        List<BlockPos> stations = new ArrayList<>(truck.stopStation);
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) idx.add(i);
        idx.sort(java.util.Comparator.comparingInt(order::get));
        truck.stopAt.clear();
        truck.stopStation.clear();
        for (int i : idx) {
            truck.stopAt.add(order.get(i));
            truck.stopStation.add(stations.get(i));
        }
    }

    /** A belt from one block to another in a line, moving from the first toward the second, at the test's fast speed. */
    private static void belt(ServerLevel level, BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        Direction along = dx != 0 ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        Direction.Axis shaft = along.getClockWise().getAxis();
        BlockState shaftState = block("create:shaft").defaultBlockState().setValue(BlockStateProperties.AXIS, shaft);
        level.setBlock(from, shaftState, Block.UPDATE_ALL);
        level.setBlock(to, shaftState, Block.UPDATE_ALL);
        BeltConnectorItem.createBelts(level, from, to);
        String company = com.meakaandre.siftec.command.LogisticsTests.beltCompany(level, (int) Math.ceil(32 * com.meakaandre.siftec.tweak.SelfMovingBelts.ITEMS_PER_MINUTE_PER_RPM));
        int length = Math.abs(dx) + Math.abs(dz);
        for (int i = 0; i <= length; i++) {
            net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(from.relative(along, i));
            if (be != null) be.setAttached(com.meakaandre.siftec.owner.Ownership.OWNER, company);
            if (be instanceof BeltBlockEntity b) com.meakaandre.siftec.tweak.SelfMovingBelts.recheck(b);
        }
    }

    private static @Nullable ControllerBlockEntity controller(ServerLevel level, BlockPos anchor, String id) {
        return com.meakaandre.siftec.building.BuildGun.controllerAt(level, anchor) instanceof ControllerBlockEntity c ? c : find(level, anchor, id);
    }

    private static @Nullable ControllerBlockEntity find(ServerLevel level, BlockPos anchor, String id) {
        for (BlockPos p : BlockPos.betweenClosed(anchor.offset(-4, -1, -4), anchor.offset(10, 4, 4))) {
            if (level.getBlockEntity(p) instanceof ControllerBlockEntity c && c.buildingId.equals(id) && anchor.equals(c.anchor)) return c;
        }
        return null;
    }

    /** The burners have lit: the miners go down in both twins at once, and twin B is let go. */
    private static void start(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        ServerLevel level = server.overworld();
        for (Twin t : List.of(A, B)) {
            ControllerBlockEntity burner = controller(level, t.burner(), "biomass_burner");
            expect(source, (t == A ? "twin A" : "twin B") + ": its Biomass Burner gives power before the miner goes down",
                burner != null && burner.offered > 0, burner == null ? "no burner" : BuildingLogic.status(level, burner).getString());
            ControllerBlockEntity miner = put(level, source, "miner_mk1", t.miner(), Direction.EAST);
            ControllerBlockEntity pole = controller(level, t.pole(), "power_pole_mk3");
            boolean wired = PowerTests.wire(PowerTests.connector(level, miner), PowerTests.connector(level, pole));
            expect(source, (t == A ? "twin A" : "twin B") + ": the Miner Mk.1 goes down on the node and is wired", miner != null && wired, "");
            // everything in the twin is recorded now, belts and all (a building is otherwise read within a few seconds)
            for (BlockPos at : new BlockPos[]{t.miner(), t.smelter(), t.constructor(), t.stationL(), t.stationU(), t.storage(), t.burner()}) {
                for (String id : new String[]{"miner_mk1", "smelter", "constructor", "truck_station", "storage_container", "biomass_burner"}) {
                    ControllerBlockEntity c = controller(level, at, id);
                    if (c != null) Factories.recordNow(level, c);
                }
            }
        }
        // twin B: its chunks are let go, so they stop ticking and unload; nothing loads them again
        int let = 0;
        for (int cx = B.x0() >> 4; cx <= B.x1() >> 4; cx++)
            for (int cz = B.z0Area() >> 4; cz <= B.z1Area() >> 4; cz++) {
                level.setChunkForced(cx, cz, false);
                let++;
            }
        watchB = true;
        bLoads = 0;
        long began = level.getGameTime();
        report(source, "twin B let go (" + let + " chunks); ten in-game minutes from " + began);
        server.tickRateManager().requestGameToSprint(RUN);
        // a moment later: B is unloaded and simulated
        later(server, 200, () -> {
            int loaded = loadedB(level), ticking = 0;
            for (int cx = B.x0() >> 4; cx <= B.x1() >> 4; cx++)
                for (int cz = B.z0Area() >> 4; cz <= B.z1Area() >> 4; cz++) if (level.shouldTickBlocksAt(new BlockPos(cx << 4, Y, cz << 4))) ticking++;
            List<SimData.Node> nodes = nodes(server, B);
            long sim = nodes.stream().filter(n -> n.sim).count();
            expect(source, "twin B: its chunks stopped ticking and its buildings are simulated", ticking == 0 && sim >= 6 && sim == nodes.size(),
                ticking + " chunks ticking, " + loaded + " still loaded, " + sim + " of " + nodes.size() + " buildings simulated");
            SimData.Vehicle v = truckB == null ? null : SimData.get(server).vehicle(truckB.toString());
            expect(source, "twin B: its Truck drives its route by time", v != null, v == null ? "not in the simulation" : "at " + Math.round(v.x) + ", " + Math.round(v.z));
            report(source, Factories.stats().line());
            describe(source, server, B);
        });
        later(server, RUN / 2, () -> describe(source, server, B));
        later(server, RUN, () -> check(source, began, false));
    }

    // ------------------------------------------------------------------------------------------------ counting

    /** Each simulated building of a twin as the simulation holds it, and its links, for working out a failure. */
    private static void describe(CommandSourceStack source, MinecraftServer server, Twin t) {
        SimData data = SimData.get(server);
        for (SimData.Node n : nodes(server, t)) {
            int links = 0;
            for (SimData.Link l : data.stored.links) if (l.from.equals(n.key())) links++;
            report(source, "node " + n.building + " at " + n.blockPos().toShortString() + ": sim " + n.sim + ", mode " + n.mode + ", recipes " + n.recipes.size()
                + ", powered " + n.powered + ", su " + n.su + ", in " + n.in + ", out " + n.out + ", made " + n.made + ", mined " + n.mined + ", mine " + n.mineItem + "/" + n.minePeriod
                + ", energy " + Math.round(n.energy) + ", tripped " + n.tripped + ", caps " + n.inCap + "/" + n.outCap + ", connectors " + n.connectors.size() + ", links out " + links
                + ", simTime " + n.simTime);
        }
    }

    private static int loadedB(ServerLevel level) {
        int loaded = 0;
        StringBuilder which = new StringBuilder();
        for (int cx = B.x0() >> 4; cx <= B.x1() >> 4; cx++)
            for (int cz = B.z0Area() >> 4; cz <= B.z1Area() >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                loaded++;
                which.append(' ').append(cx).append(',').append(cz).append(" [").append(level.getChunkSource().getChunkDebugData(new ChunkPos(cx, cz)))).append(']');
            }
        if (loaded > 0) Siftec.LOGGER.info("SELFTEST always twin B chunks still loaded:{} (truck set off in {}, {}); forced {}", which,
            (B.ox + 16) >> 4, (B.oz + 3) >> 4, level.getForceLoadedChunks().size());
        return loaded;
    }

    /** The simulation's nodes of a twin. */
    private static List<SimData.Node> nodes(MinecraftServer server, Twin t) {
        List<SimData.Node> out = new ArrayList<>();
        for (SimData.Node n : SimData.get(server).stored.nodes.values()) {
            BlockPos p = n.blockPos();
            if (n.dim.equals("minecraft:overworld") && n.kind.equals("building") && t.holds(p.getX(), p.getZ()) && Math.abs(p.getY() - Y) <= 3) out.add(n);
        }
        return out;
    }

    /** Rods the simulation holds for a twin: in its buildings, on its belts, in its truck. */
    private static long simRods(MinecraftServer server, Twin t, @Nullable UUID truck, StringBuilder detail) {
        SimData data = SimData.get(server);
        long n = 0;
        for (SimData.Node node : nodes(server, t)) {
            long here = Sim.get(node.out, ROD) + Sim.get(node.in, ROD);
            if (here > 0) detail.append(node.building).append(' ').append(here).append(", ");
            n += here;
        }
        long belts = 0;
        for (SimData.Link l : data.stored.links) {
            SimData.Node from = data.node(l.from);
            if (from == null || !t.holds(from.blockPos().getX(), from.blockPos().getZ())) continue;
            for (SimData.Transit tr : l.transit) if (tr.key.equals(ROD)) belts += tr.n;
        }
        n += belts;
        long cargo = 0;
        SimData.Vehicle v = truck == null ? null : data.vehicle(truck.toString());
        if (v != null) for (SimData.Slot s : v.cargo) if (s.key.equals(ROD)) cargo += s.count;
        n += cargo;
        detail.append("belts ").append(belts).append(", truck ").append(cargo).append(v == null ? " (not simulated)" : "");
        return n;
    }

    /** Rods a twin holds in the world: the same places, read from the blocks and the truck. */
    private static long worldRods(ServerLevel level, Twin t, @Nullable UUID truck, StringBuilder detail) {
        Item rod = item(ROD);
        long n = 0;
        for (Object[] b : new Object[][]{{t.constructor(), "constructor"}, {t.stationL(), "truck_station"}, {t.stationU(), "truck_station"}, {t.storage(), "storage_container"}}) {
            ControllerBlockEntity c = controller(level, (BlockPos) b[0], (String) b[1]);
            long here = c == null ? 0 : held(level, c, rod);
            if (here > 0) detail.append(b[1]).append(' ').append(here).append(", ");
            n += here;
        }
        long belts = 0;
        for (BlockPos[] b : t.belts()) {
            if (level.getBlockEntity(b[0]) instanceof BeltBlockEntity belt && belt.getInventory() != null) {
                for (var it : belt.getInventory().getTransportedItems()) if (it.stack.is(rod)) belts += it.stack.getCount();
            }
        }
        n += belts;
        long cargo = 0;
        VehicleEntity v = truck == null ? null : level.getEntity(truck) instanceof VehicleEntity e ? e : null;
        if (v != null) for (int i = 0; i < v.cargo.getContainerSize(); i++) if (v.cargo.getItem(i).is(rod)) cargo += v.cargo.getItem(i).getCount();
        n += cargo;
        detail.append("belts ").append(belts).append(", truck ").append(cargo).append(v == null ? " (not loaded)" : "");
        return n;
    }

    /** Everything of an item a building holds: its Basin, its vaults, its controller's slots. */
    private static long held(ServerLevel level, ControllerBlockEntity c, Item item) {
        long n = 0;
        Set<Object> seen = new HashSet<>();
        for (Container slots : new Container[]{c.input, c.output, c.buffer}) for (int i = 0; i < slots.getContainerSize(); i++) if (slots.getItem(i).is(item)) n += slots.getItem(i).getCount();
        for (BlockPos pos : BuildingLogic.cells(c, block("create:basin"))) {
            if (!(level.getBlockEntity(pos) instanceof BasinBlockEntity basin)) continue;
            for (int i = 0; i < basin.itemCapability.getContainerSize(); i++) if (basin.itemCapability.getItem(i).is(item)) n += basin.itemCapability.getItem(i).getCount();
        }
        for (BlockPos pos : BuildingLogic.cells(c, block("create:item_vault"))) {
            Storage<ItemVariant> s = ItemStorage.SIDED.find(level, pos, null);
            if (s == null || !seen.add(s)) continue;
            for (StorageView<ItemVariant> v : s) if (!v.isResourceBlank() && v.getResource().getItem() == item) n += v.getAmount();
        }
        return n;
    }

    private static long truckStops(MinecraftServer server, ServerLevel level, @Nullable UUID truck) {
        if (truck == null) return -1;
        if (level.getEntity(truck) instanceof VehicleEntity v) return v.stopsMade;
        SimData.Vehicle sv = SimData.get(server).vehicle(truck.toString());
        return sv == null ? -1 : sv.stopsMade;
    }

    /** Ten minutes on (or after the restart): twin A, read from the world, against twin B, read from the simulation. */
    private static void check(CommandSourceStack source, long began, boolean afterRestart) {
        MinecraftServer server = source.getServer();
        ServerLevel level = server.overworld();
        server.tickRateManager().stopSprinting();
        String tag = afterRestart ? "after the restart: " : "after ten minutes: ";
        StringBuilder da = new StringBuilder(), db = new StringBuilder();
        long a = worldRods(level, A, truckA, da), b = simRods(server, B, truckB, db);
        ControllerBlockEntity conA = controller(level, A.constructor(), "constructor");
        report(source, tag + "twin A " + a + " rods (" + da + "), constructor made " + (conA == null ? "?" : conA.made) + "; twin B " + b + " rods (" + db + ")");
        expect(source, tag + "both twins made rods at the Constructor's rate (about 15 a minute)", a >= 100 && b >= 100, a + " and " + b);
        expect(source, tag + "the unloaded twin holds the same rods as the loaded one, within one craft", Math.abs(a - b) <= 1, a + " against " + b);
        expect(source, tag + "no chunk of twin B was loaded meanwhile, and none is loaded now", bLoads == 0 && loadedB(level) == 0, bLoads + " loads, " + loadedB(level) + " loaded now");
        long stopsA = truckStops(server, level, truckA), stopsB = truckStops(server, level, truckB);
        expect(source, tag + "both Trucks drove their loops, stopping at the stations", stopsA >= 4 && stopsB >= 4, "A " + stopsA + " stops, B " + stopsB);
        long sim = nodes(server, B).stream().filter(n -> n.sim).count();
        expect(source, tag + "twin B is still simulated", sim >= 6, sim + " buildings");
        report(source, Factories.stats().line());
        if (!afterRestart) {
            bench(source);
            report(source, "result: " + passed + " passed, " + failed + " failed");
        }
    }

    // ------------------------------------------------------------------------------------------------ after a restart

    private static int restart(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ServerLevel level = server.overworld();
        passed = failed = 0;
        watchB = true;
        bLoads = 0;
        if (truckA == null || truckB == null) {
            // the UUIDs are found again from the simulation and the world
            for (SimData.Vehicle v : SimData.get(server).stored.vehicles) if (B.holds((int) v.x, (int) v.z)) truckB = UUID.fromString(v.uuid);
            for (VehicleEntity v : level.getEntitiesOfClass(VehicleEntity.class, new AABB(A.x0(), Y - 5, A.z0Area(), A.x1(), Y + 10, A.z1Area()))) truckA = v.getUUID();
        }
        later(server, 100, () -> {
            check(source, 0, true);
            // now twin B is loaded again: its catch-up writes what the simulation did into its buildings
            watchB = false;
            for (int cx = B.x0() >> 4; cx <= B.x1() >> 4; cx++) for (int cz = B.z0Area() >> 4; cz <= B.z1Area() >> 4; cz++) level.setChunkForced(cx, cz, true);
            for (int cx = B.x0() >> 4; cx <= B.x1() >> 4; cx++) for (int cz = B.z0Area() >> 4; cz <= B.z1Area() >> 4; cz++) level.getChunk(cx, cz);
            later(server, 80, () -> {
                long active = nodes(server, B).stream().filter(n -> !n.sim).count();
                StringBuilder da = new StringBuilder(), db = new StringBuilder();
                long a = worldRods(level, A, truckA, da), b = worldRods(level, B, truckB, db);
                report(source, "loaded again: twin A " + a + " rods (" + da + "); twin B " + b + " rods (" + db + ")");
                expect(source, "loaded again: twin B's buildings run for real again", active >= 6, active + " buildings");
                expect(source, "loaded again: the catch-up gave twin B the same rods as twin A, within one craft", Math.abs(a - b) <= 1, a + " against " + b);
                boolean back = truckB != null && level.getEntity(truckB) instanceof VehicleEntity;
                expect(source, "loaded again: twin B's Truck is back in the world on its route", back, back ? "" : "not found");
                report(source, Factories.stats().line());
                report(source, "restart result: " + passed + " passed, " + failed + " failed");
            });
        });
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ the cost

    /**
     * 200 simulated buildings (100 miners each feeding a Constructor by belt, as the simulation holds them, far from
     * anything loaded) run for 400 ticks: what the simulation costs a tick, on the server thread and on its worker, has to
     * be under 1 ms. They are taken away again afterwards.
     */
    private static void bench(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        // a set of its own, apart from the world's: nothing real is stepped ahead
        SimData data = new SimData();
        List<String> made = new ArrayList<>();
        long now = server.overworld().getGameTime();
        for (int i = 0; i < 100; i++) {
            BlockPos mp = new BlockPos(-30_000 + i * 64, 100, 30_000), cp = mp.east(16);
            SimData.Node m = data.nodeOrNew(server.overworld(), mp, "building");
            m.building = "miner_mk1";
            m.logic = "miner";
            m.mode = "mine";
            m.mineItem = "minecraft:raw_iron";
            m.minePeriod = 32;
            m.minePer = 1;
            m.outCap = 64;
            m.sim = true;
            m.simTime = now;
            m.recordedAt = now;
            SimData.Node c = data.nodeOrNew(server.overworld(), cp, "building");
            c.building = "constructor";
            c.logic = "basin";
            c.mode = "craft";
            c.inCap = 576;
            c.outCap = 576;
            SimData.Recipe r = new SimData.Recipe();
            r.in.put("minecraft:raw_iron", 1L);
            r.out.put("minecraft:iron_ingot", 1L);
            r.ticks = 80;
            r.outDelay = 20;
            c.recipes.add(r);
            c.sim = true;
            c.simTime = now;
            c.recordedAt = now;
            SimData.Link l = new SimData.Link();
            l.dim = m.dim;
            l.from = m.key();
            l.to = c.key();
            l.rate = 0.07;
            l.delay = 60;
            l.cap = 8;
            data.stored.links.add(l);
            made.add(m.key());
            made.add(c.key());
        }
        // a few steps on the worker as it runs, timed by the simulation's own clock, then a tight run of steps
        Factories.Stats before = Factories.stats();
        int steps = 40;
        long t0 = System.nanoTime();
        for (int i = 1; i <= steps; i++) Factories.benchStep(server, data, now + (long) i * Factories.STEP);
        long took = System.nanoTime() - t0;
        double perTick = took / 1e6 / steps / Factories.STEP;
        long rods = 0;
        for (String k : made) {
            SimData.Node n = data.node(k);
            if (n != null) rods += Sim.get(n.out, "minecraft:iron_ingot") + Sim.get(n.out, "minecraft:raw_iron");
        }
        expect(source, "200 simulated buildings cost less than 1 ms a tick (" + steps + " steps of " + Factories.STEP + " ticks, the checks of every tick, the groups and the step, all on the server thread here)",
            perTick < 1.0, String.format(java.util.Locale.ROOT, "%.4f ms a tick, %.3f ms a step; %d things made; live: %s", perTick, perTick * Factories.STEP, rods, before.line()));
    }
}
