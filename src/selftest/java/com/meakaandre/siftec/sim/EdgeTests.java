package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.BuildingPower;
import com.meakaandre.siftec.building.BuildingTemplate;
import com.meakaandre.siftec.building.BuildingTests;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.Placement;
import com.meakaandre.siftec.building.PortBlock;
import com.meakaandre.siftec.building.SimAccess;
import com.meakaandre.siftec.building.SpecialBuildings;
import com.meakaandre.siftec.command.PolishTests;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.drone.DronePortBlockEntity;
import com.meakaandre.siftec.drone.PortContainer;
import com.meakaandre.siftec.power.PowerTests;
import com.meakaandre.siftec.save.JsonSavedData;
import com.meakaandre.siftec.sink.Sink;
import com.meakaandre.siftec.vehicle.VehicleKind;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.content.logistics.box.PackageItem;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

/**
 * The always-running simulation's edges, {@code /siftec selftest always edges}: what happens to a building, its belts
 * and its power as it moves between running for real and being simulated. Each case first sets up the moment something
 * could be doubled or lost, then counts. Every line says "SELFTEST always edges"; failures say FAIL.
 * <p>
 * Each case has a site of its own far from everything, a column of chunks one force-loaded chunk F heads: the row south
 * of F ticks (it is next to F), the row after that is loaded but does not tick (two from F), and anything further is
 * not loaded. Forcing a site's whole area makes all of it tick; letting it all go unloads it.
 */
public final class EdgeTests {
    private static final int Y = 150;
    /** Each case's F chunk: column CX, row CZ0 + 12 a case. */
    private static final int CX = -900, CZ0 = -900, SPACING = 12;
    private static final Item IRON = Items.IRON_INGOT;

    private record Later(long at, Runnable run) {
    }

    private record Poll(BooleanSupplier until, long giveUpAt, Runnable then) {
    }

    private static final List<Later> LATER = new ArrayList<>();
    private static final List<Poll> POLLS = new ArrayList<>();
    private static int passed, failed, open;
    private static @Nullable CommandSourceStack source;
    /** Chunk loads seen of a chunk being watched (the Tracer case). */
    private static long watched = Long.MIN_VALUE;
    private static int watchedLoads;

    private EdgeTests() {
    }

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = server.overworld().getGameTime();
            for (Later l : new ArrayList<>(LATER)) {
                if (l.at > now) continue;
                LATER.remove(l);
                run(l.run);
            }
            for (Poll p : new ArrayList<>(POLLS)) {
                if (!p.until.getAsBoolean() && now < p.giveUpAt) continue;
                POLLS.remove(p);
                run(p.then);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LATER.clear();
            POLLS.clear();
            watched = Long.MIN_VALUE;
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            if (watched != Long.MIN_VALUE && level.dimension() == Level.OVERWORLD && chunk.getPos().pack() == watched) watchedLoads++;
        });
    }

    private static void run(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            Siftec.LOGGER.error("SELFTEST always edges: a step threw", e);
            failed++;
            report("FAIL a step threw " + e);
        }
    }

    private static void later(MinecraftServer server, int ticks, Runnable run) {
        LATER.add(new Later(server.overworld().getGameTime() + ticks, run));
    }

    private static void until(MinecraftServer server, BooleanSupplier cond, int most, Runnable then) {
        POLLS.add(new Poll(cond, server.overworld().getGameTime() + most, then));
    }

    private static void report(String text) {
        String line = "SELFTEST always edges " + text;
        Siftec.LOGGER.info(line);
        if (source != null) source.sendSuccess(() -> Component.literal(line), false);
    }

    private static void expect(String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        report((ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    /** A case is under way; when the last one is done the totals are printed. */
    private static void opened() {
        open++;
    }

    private static void closed() {
        if (--open == 0) report("result: " + passed + " passed, " + failed + " failed");
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    // ------------------------------------------------------------------------------------------------ sites

    /** A case's site: F, the force-loaded chunk heading it. */
    private record Site(int cx, int cz) {
        int x(int chunkDx) {
            return (cx + chunkDx) << 4;
        }

        int z(int chunkDz) {
            return (cz + chunkDz) << 4;
        }

        /** Everything the case uses: three columns either side, and the rows down to four after F. */
        AABB box() {
            return new AABB(x(-2), Y - 2, z(0), x(4), Y + 30, z(4));
        }
    }

    private static Site site(int n) {
        return new Site(CX, CZ0 + n * SPACING);
    }

    /** All of a site ticks. */
    private static void all(ServerLevel level, Site s) {
        for (int dx = -2; dx <= 3; dx++) for (int dz = 0; dz <= 3; dz++) {
            level.setChunkForced(s.cx + dx, s.cz + dz, true);
            level.getChunk(s.cx + dx, s.cz + dz);
        }
    }

    /** Only F is force-loaded: the row after it ticks, the one after that is loaded but does not tick. */
    private static void ring(ServerLevel level, Site s) {
        for (int dx = -2; dx <= 3; dx++) for (int dz = 0; dz <= 3; dz++) if (dx != 0 || dz != 0) level.setChunkForced(s.cx + dx, s.cz + dz, false);
        level.setChunkForced(s.cx, s.cz, true);
    }

    /** Nothing of the site is force-loaded: it unloads. */
    private static void none(ServerLevel level, Site s) {
        for (int dx = -2; dx <= 3; dx++) for (int dz = 0; dz <= 3; dz++) level.setChunkForced(s.cx + dx, s.cz + dz, false);
    }

    private static boolean ticks(ServerLevel level, int cx, int cz) {
        return level.hasChunk(cx, cz) && level.shouldTickBlocksAt(ChunkPos.pack(cx, cz));
    }

    private static boolean ticks(ServerLevel level, BlockPos pos) {
        return ticks(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** True once no chunk of the site is loaded. */
    private static boolean unloaded(ServerLevel level, Site s) {
        for (int dx = -2; dx <= 3; dx++) for (int dz = 0; dz <= 3; dz++) if (level.hasChunk(s.cx + dx, s.cz + dz)) return false;
        return true;
    }

    private static String state(ServerLevel level, BlockPos pos) {
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        return pos.toShortString() + " " + (!level.hasChunk(cx, cz) ? "unloaded" : ticks(level, cx, cz) ? "ticking" : "loaded, not ticking");
    }

    private static void ground(ServerLevel level, Site s) {
        BuildingTests.platform(level, s.x(-1), s.z(0), s.x(3) - 1, s.z(4) - 1, Y, 14);
    }

    // ------------------------------------------------------------------------------------------------ buildings

    private static @Nullable Placement placement(String id, BlockPos anchor, Direction facing) {
        BuildingDef def = Buildings.def(id);
        return def == null ? null : new Placement(def, anchor, facing, false);
    }

    /** Every cell a building would take, and its controller's, at this anchor. */
    private static List<BlockPos> cells(String id, BlockPos anchor, Direction facing) {
        List<BlockPos> out = new ArrayList<>();
        Placement p = placement(id, anchor, facing);
        BuildingTemplate t = p == null ? null : BuildingTemplate.of(p.def());
        if (t == null) return out;
        for (BuildingTemplate.Cell c : t.members) out.add(p.at(c));
        out.add(p.at(t.controller));
        return out;
    }

    private static @Nullable BlockPos controllerAt(String id, BlockPos anchor, Direction facing) {
        Placement p = placement(id, anchor, facing);
        BuildingTemplate t = p == null ? null : BuildingTemplate.of(p.def());
        return t == null ? null : p.at(t.controller);
    }

    /** A port of a building at this anchor: where it is and which way it faces out. */
    private record Port(BlockPos pos, Direction facing) {
    }

    private static @Nullable Port port(String id, BlockPos anchor, Direction facing, PortBlock.Kind kind) {
        Placement p = placement(id, anchor, facing);
        BuildingTemplate t = p == null ? null : BuildingTemplate.of(p.def());
        if (t == null) return null;
        for (BuildingTemplate.Cell c : t.members) {
            if (!(c.state().getBlock() instanceof PortBlock) || c.state().getValue(PortBlock.KIND) != kind) continue;
            return new Port(p.at(c), p.orient(c.state().getValue(PortBlock.FACING)));
        }
        return null;
    }

    /** An anchor in chunk (cx, cz) where the building fits wholly inside that chunk, clear of what is taken. */
    private static @Nullable BlockPos fit(String id, Direction facing, int cx, int cz, Set<BlockPos> taken) {
        for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
            BlockPos anchor = new BlockPos((cx << 4) + dx, Y + 1, (cz << 4) + dz);
            List<BlockPos> cells = cells(id, anchor, facing);
            if (cells.isEmpty()) return null;
            boolean ok = true;
            for (BlockPos c : cells) {
                if (c.getX() >> 4 != cx || c.getZ() >> 4 != cz || taken.contains(c) || taken.contains(c.north()) || taken.contains(c.west())) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            taken.addAll(cells);
            return anchor;
        }
        return null;
    }

    private static @Nullable ControllerBlockEntity put(ServerLevel level, String id, @Nullable BlockPos anchor, Direction facing) {
        if (anchor == null || source == null) return null;
        return AlwaysTests.put(level, source, id, anchor, facing);
    }

    /** Two Storage Containers joined by a belt: from the first's output port, {@code len} long, into the second's input. */
    private record Pair(ControllerBlockEntity a, ControllerBlockEntity b, BlockPos beltFrom, BlockPos beltTo) {
    }

    private static @Nullable Pair pair(ServerLevel level, BlockPos anchorA, Direction facing, int len) {
        Port out = port("storage_container", anchorA, facing, PortBlock.Kind.ITEM_OUT), in = port("storage_container", anchorA, facing, PortBlock.Kind.ITEM_IN);
        if (out == null || in == null) return null;
        BlockPos target = out.pos.relative(out.facing, len + 1);
        BlockPos anchorB = anchorA.offset(target.subtract(in.pos));
        ControllerBlockEntity a = put(level, "storage_container", anchorA, facing), b = put(level, "storage_container", anchorB, facing);
        if (a == null || b == null) return null;
        BlockPos from = out.pos.relative(out.facing), to = out.pos.relative(out.facing, len);
        AlwaysTests.belt(level, from, to);
        return new Pair(a, b, from, to);
    }

    /** Where a pair would stand with A at this anchor: A, the belt and B's cells. */
    private static List<BlockPos> pairCells(BlockPos anchorA, Direction facing, int len) {
        List<BlockPos> out = new ArrayList<>(cells("storage_container", anchorA, facing));
        Port o = port("storage_container", anchorA, facing, PortBlock.Kind.ITEM_OUT), in = port("storage_container", anchorA, facing, PortBlock.Kind.ITEM_IN);
        if (o == null || in == null) return out;
        for (int i = 1; i <= len; i++) out.add(o.pos.relative(o.facing, i));
        BlockPos target = o.pos.relative(o.facing, len + 1);
        out.addAll(cells("storage_container", anchorA.offset(target.subtract(in.pos)), facing));
        return out;
    }

    private static long fill(ServerLevel level, ControllerBlockEntity c, Item item, long count) {
        long put = 0;
        Set<Object> seen = new HashSet<>();
        for (BlockPos vault : SimAccess.cells(c, "create:item_vault")) {
            Storage<ItemVariant> s = ItemStorage.SIDED.find(level, vault, null);
            if (s == null || !seen.add(s)) continue;
            try (Transaction tx = Transaction.openOuter()) {
                put += s.insert(ItemVariant.of(item), count - put, tx);
                tx.commit();
            }
            if (put >= count) break;
        }
        return put;
    }

    /** Everything of an item in a box: buildings (vaults, Basins, slots), belts and items lying about. */
    private static long count(ServerLevel level, AABB box, Item item) {
        long n = 0;
        BlockPos min = BlockPos.containing(box.minX, box.minY, box.minZ), max = BlockPos.containing(box.maxX - 1, box.maxY - 1, box.maxZ - 1);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!level.isLoaded(pos)) continue;
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ControllerBlockEntity c) n += AlwaysTests.held(level, c, item);
            else if (be instanceof BeltBlockEntity b && b.isController() && b.getInventory() != null) {
                for (var t : b.getInventory().getTransportedItems()) if (t.stack.is(item)) n += t.stack.getCount();
            }
        }
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) if (e.getItem().is(item)) n += e.getItem().getCount();
        return n;
    }

    private static int links(SimData data, String key) {
        int n = 0;
        for (SimData.Link l : data.stored.links) if (l.from.equals(key)) n++;
        return n;
    }

    private static long carried(SimData data, String key) {
        long n = 0;
        for (SimData.Link l : data.stored.links) if (l.from.equals(key) || l.to.equals(key)) n += l.carrying();
        return n;
    }

    // ------------------------------------------------------------------------------------------------ the run

    static int start(CommandContext<CommandSourceStack> context) {
        source = context.getSource();
        MinecraftServer server = source.getServer();
        ServerLevel level = server.overworld();
        passed = failed = open = 0;
        LATER.clear();
        POLLS.clear();
        AlwaysTests.company(server);
        report("sites from chunk " + CX + ", " + CZ0 + "; the lagging-server cases first, then the rest at once");
        opened();
        lagging(server, level, site(0));
        // the logout case holds the worker until its site has unloaded; the others start once it lets go
        opened();
        logout(server, level, site(1), () -> {
            for (Runnable r : List.<Runnable>of(
                () -> sinkFirstTick(server, level, site(2)),
                () -> straddling(server, level, site(3)),
                () -> portEmptied(server, level, site(4)),
                () -> portBroken(server, level, site(5)),
                () -> transitHandover(server, level, site(6)),
                () -> deadLink(server, level, site(7)),
                () -> unrecordedTarget(server, level, site(8)),
                () -> battery(server, level, site(9)),
                () -> nuclear(server, level, site(10)),
                () -> tracer(server, level, site(11)),
                () -> truck(server, level, site(12)),
                () -> saving(server, level),
                () -> allowance(server, level))) {
                opened();
                run(r);
            }
        });
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ handover

    /**
     * A lagging server: the worker is still on a step whenever a building's turn to be read comes round. What came into
     * the building meanwhile must be read as soon as the step is done, not at its next turn.
     */
    private static void lagging(MinecraftServer server, ServerLevel level, Site s) {
        all(level, s);
        ground(level, s);
        Set<BlockPos> taken = new HashSet<>();
        ControllerBlockEntity a = put(level, "storage_container", fit("storage_container", Direction.EAST, s.cx, s.cz + 1, taken), Direction.EAST);
        if (a == null) {
            expect("lagging: the Storage Container goes down", false, "");
            closed();
            return;
        }
        fill(level, a, IRON, 64);
        Factories.recordNow(level, a);
        BlockPos at = a.getBlockPos();
        later(server, 30, () -> {
            CountDownLatch release = new CountDownLatch(1);
            Factories.holdWorker(release, 15_000);
            fill(level, a, IRON, 64);
            long now = level.getGameTime(), bits = at.asLong() & 0x7FFF;
            // let go just after its turn: its next turn is nine ticks off
            long r = now + 12;
            while ((r + bits) % Factories.STEP != 1) r++;
            later(server, (int) (r - now), release::countDown);
            later(server, (int) (r - now) + 4, () -> {
                SimData.Node n = Factories.node(level, at);
                long seen = n == null ? -1 : Sim.get(n.out, Keys.of(IRON));
                expect("lagging: what came into a building while the worker was busy at its turn is read as soon as the step is done",
                    seen == 128, "the simulation sees " + seen + " of 128 iron");
                none(level, s);
                closed();
            });
        });
    }

    /**
     * Logout mid-cycle: a Storage Container sends iron along a belt to another; the worker is busy (a lagging server) as
     * the site's chunks stop ticking and unload, so the simulation starts from what the buildings last held. Simulated a
     * while and loaded again, the site must hold exactly the iron it began with.
     */
    private static void logout(MinecraftServer server, ServerLevel level, Site s, Runnable next) {
        all(level, s);
        ground(level, s);
        BlockPos anchor = null;
        for (int dx = 15; dx >= 0 && anchor == null; dx--) {
            BlockPos at = new BlockPos(s.x(0) + dx, Y + 1, s.z(1) + 4);
            boolean ok = true;
            for (BlockPos c : pairCells(at, Direction.EAST, 4)) if (c.getZ() >> 4 != s.cz + 1 || Math.abs((c.getX() >> 4) - s.cx) > 1) ok = false;
            if (ok) anchor = at;
        }
        Pair p = anchor == null ? null : pair(level, anchor, Direction.EAST, 4);
        if (p == null) {
            expect("logout: two Storage Containers and a belt go down", false, "");
            closed();
            next.run();
            return;
        }
        long start = fill(level, p.a, IRON, 256);
        AABB box = s.box();
        // read once the belt has started moving (a belt that does not move is no link)
        later(server, 40, () -> {
            Factories.recordNow(level, p.a);
            Factories.recordNow(level, p.b);
            report("logout: " + links(SimData.get(server), SimData.key(level, p.a.getBlockPos())) + " link(s) from the first container");
        });
        later(server, 60, () -> {
            CountDownLatch release = new CountDownLatch(1);
            Factories.holdWorker(release, 15_000);
            long held = level.getGameTime();
            later(server, 40, () -> {
                long moved = AlwaysTests.held(level, p.b, IRON);
                none(level, s);
                until(server, () -> unloaded(level, s), 600, () -> {
                    release.countDown();
                    report("logout: worker busy for " + (level.getGameTime() - held) + " ticks, the site " + (unloaded(level, s) ? "unloaded" : "STILL LOADED") + "; " + moved + " iron had reached the second container");
                    next.run();
                    later(server, 400, () -> {
                        all(level, s);
                        later(server, 120, () -> {
                            long now = count(level, box, IRON);
                            expect("logout mid-cycle: loaded again, the site holds the iron it began with (nothing made twice from a stale snapshot)",
                                now == start, now + " against " + start + "; left on belts in the simulation " + carried(SimData.get(server), SimData.key(level, p.a.getBlockPos())));
                            none(level, s);
                            closed();
                        });
                    });
                });
            });
        });
    }

    /**
     * A building whose chunk ticks again must be caught up before its first real tick: an AWESOME Sink with iron plates
     * in its input stops ticking (loaded, out of simulation distance) in the tick they arrive; the simulation sinks them;
     * when it ticks again the real Sink must not sink them a second time.
     */
    private static void sinkFirstTick(MinecraftServer server, ServerLevel level, Site s) {
        all(level, s);
        ground(level, s);
        Set<BlockPos> taken = new HashSet<>();
        int nx = s.cx, nz = s.cz + 2;
        ControllerBlockEntity sink = put(level, "awesome_sink", fit("awesome_sink", Direction.EAST, nx, nz, taken), Direction.EAST);
        ControllerBlockEntity burner = put(level, "biomass_burner", fit("biomass_burner", Direction.EAST, nx, nz, taken), Direction.EAST);
        boolean wired = sink != null && burner != null && PowerTests.wire(PowerTests.connector(level, sink), PowerTests.connector(level, burner));
        if (!wired) {
            expect("sink: an AWESOME Sink and a Biomass Burner go down in one chunk, wired", false, (sink == null ? "no sink " : "") + (burner == null ? "no burner" : ""));
            closed();
            return;
        }
        burner.input.setItem(0, new ItemStack(item("siftec:solid_biofuel"), 64));
        Factories.recordNow(level, sink);
        Factories.recordNow(level, burner);
        var company = CompanyData.get(server).byId(sink.companyId);
        String owner = sink.companyId;
        later(server, 200, () -> {
            ItemStack plates = ItemStack.EMPTY;
            for (String id : new String[]{"siftec:iron_plate", "siftec:iron_rod", "siftec:screw", "siftec:reinforced_iron_plate", "minecraft:iron_ingot"}) {
                ItemStack one = new ItemStack(item(id));
                if (!one.isEmpty() && Sink.accepts(one) && Sink.worthOne(one) > 0) {
                    plates = one.copyWithCount(32);
                    break;
                }
            }
            long worth = Sink.worth(plates);
            long before = company == null ? 0 : company.pointsEver;
            boolean powered = sink.powered;
            // the plates arrive just as the chunk stops ticking (it stays loaded through F): the real Sink is kept from
            // sinking them in its last tick (it sinks nothing without a company), so the simulation starts with them
            sink.companyId = "";
            sink.input.setItem(0, plates);
            for (int dx = -2; dx <= 3; dx++) for (int dz = 0; dz <= 3; dz++) if (dx != 0 || dz != 0) level.setChunkForced(s.cx + dx, s.cz + dz, false);
            level.setChunkForced(s.cx, s.cz, true);
            later(server, 100, () -> {
                SimData.Node n = Factories.node(level, sink.getBlockPos());
                report("sink: powered before " + powered + "; its chunk " + state(level, sink.getBlockPos()) + ", simulated " + (n != null && n.sim)
                    + ", the simulation sank " + (n == null ? "?" : n.made) + "; input " + sink.input.getItem(0));
                sink.companyId = owner;
                level.setChunkForced(nx, nz, true);
                later(server, 60, () -> {
                    long got = company == null ? -1 : company.pointsEver - before;
                    expect("sink: caught up before its first real tick, the Sink pays for its 32 items once",
                        worth > 0 && got == worth && sink.input.getItem(0).isEmpty(), got + " points against " + worth + "; input " + sink.input.getItem(0));
                    none(level, s);
                    closed();
                });
            });
        });
    }

    /**
     * A Storage Container straddling a ticking chunk and one that only is loaded: its controller does not tick, its output
     * port does. It must not both run (its port pushing out) and be simulated (the simulation shipping the same iron).
     */
    private static void straddling(MinecraftServer server, ServerLevel level, Site s) {
        all(level, s);
        ground(level, s);
        BlockPos anchor = null;
        int len = 3;
        search:
        for (int dz = 0; dz < 32; dz++) {
            for (int dx = 0; dx < 32; dx++) {
                BlockPos at = new BlockPos(s.x(-1) + 8 + dx, Y + 1, s.z(1) + dz);
                BlockPos ctrl = controllerAt("storage_container", at, Direction.EAST);
                Port out = port("storage_container", at, Direction.EAST, PortBlock.Kind.ITEM_OUT);
                if (ctrl == null || out == null || ctrl.getZ() >> 4 != s.cz + 2 || out.pos.getZ() >> 4 != s.cz + 1) continue;
                boolean ok = true;
                for (BlockPos c : pairCells(at, Direction.EAST, len)) {
                    int rz = (c.getZ() >> 4) - s.cz, rx = (c.getX() >> 4) - s.cx;
                    if (rz < 1 || rz > 2 || Math.abs(rx) > 1) ok = false;
                }
                if (ok) {
                    anchor = at;
                    break search;
                }
            }
        }
        Pair p = anchor == null ? null : pair(level, anchor, Direction.EAST, len);
        if (p == null) {
            expect("straddling: a Storage Container across a chunk border goes down", false, anchor == null ? "no anchor found" : "");
            closed();
            return;
        }
        long start = fill(level, p.a, IRON, 128);
        AABB box = s.box();
        later(server, 40, () -> {
            Factories.recordNow(level, p.a);
            Factories.recordNow(level, p.b);
            report("straddling: " + links(SimData.get(server), SimData.key(level, p.a.getBlockPos())) + " link(s) from the straddling container");
            ring(level, s);
        });
        later(server, 440, () -> {
            Port out = port("storage_container", p.a.anchor == null ? p.a.getBlockPos() : p.a.anchor, Direction.EAST, PortBlock.Kind.ITEM_OUT);
            SimData.Node n = Factories.node(level, p.a.getBlockPos());
            report("straddling: controller " + state(level, p.a.getBlockPos()) + ", output port " + (out == null ? "?" : state(level, out.pos))
                + "; simulated " + (n != null && n.sim) + "; the second container holds " + AlwaysTests.held(level, p.b, IRON));
            all(level, s);
            later(server, 120, () -> {
                long now = count(level, box, IRON);
                expect("straddling: a building half in ticking land is not run twice (its port and the simulation both shipping)",
                    now == start, now + " against " + start);
                none(level, s);
                closed();
            });
        });
    }

    private static @Nullable DronePortBlockEntity dronePort(ServerLevel level, Site s) {
        // its terminal in the loaded, not ticking row; the whole building within the two rows after F
        for (int dz = 0; dz < 16; dz++) {
            BlockPos terminal = new BlockPos(s.x(0) + 8, Y + 1, s.z(2) + dz);
            BuildingDef def = Buildings.def("drone_port");
            BuildingTemplate t = def == null ? null : BuildingTemplate.of(def);
            int[] cell = t == null ? null : SpecialBuildings.terminalCell(t);
            if (cell == null) return null;
            Placement p = SpecialBuildings.forTerminal(def, cell, terminal, Direction.NORTH);
            boolean ok = true;
            for (BuildingTemplate.Cell c : t.members) {
                BlockPos at = p.at(c);
                int rz = (at.getZ() >> 4) - s.cz;
                if (rz < 1 || rz > 2) ok = false;
            }
            if (ok) return PolishTests.buildDronePort(level, AlwaysTests.company(level.getServer()), terminal);
        }
        return null;
    }

    private static int packages(DronePortBlockEntity port, int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) if (!port.items.getItem(i).isEmpty()) n += port.items.getItem(i).getCount();
        return n;
    }

    /** A Drone Port whose arrivals a player takes while its chunk does not tick: the catch-up must not bring them back. */
    private static void portEmptied(MinecraftServer server, ServerLevel level, Site s) {
        all(level, s);
        ground(level, s);
        DronePortBlockEntity port = dronePort(level, s);
        if (port == null) {
            expect("emptied port: a Drone Port goes down", false, "");
            closed();
            return;
        }
        BlockPos at = port.getBlockPos();
        port.companyId = AlwaysTests.company(server).id;
        for (int i = 0; i < 3; i++) port.items.setItem(PortContainer.IN + i, PackageItem.containing(List.of(new ItemStack(Items.GOLD_INGOT, 2))));
        port.setChanged();
        later(server, 60, () -> {
            ring(level, s);
            later(server, 60, () -> {
                SimData.Node n = Factories.node(level, at);
                DronePortBlockEntity p = level.getBlockEntity(at) instanceof DronePortBlockEntity d ? d : null;
                report("emptied port: terminal " + state(level, at) + ", simulated " + (n != null && n.sim));
                if (p != null) {
                    // a player takes the arrivals
                    for (int i = PortContainer.IN; i < PortContainer.SIZE; i++) p.items.removeItemNoUpdate(i);
                    p.setChanged();
                }
                later(server, 20, () -> {
                    level.setChunkForced(at.getX() >> 4, at.getZ() >> 4, true);
                    later(server, 60, () -> {
                        DronePortBlockEntity q = level.getBlockEntity(at) instanceof DronePortBlockEntity d ? d : null;
                        int back = q == null ? -1 : packages(q, PortContainer.IN, PortContainer.SIZE);
                        expect("emptied port: arrivals taken while the port was simulated do not come back with the catch-up", back == 0, back + " packages in its arrivals");
                        none(level, s);
                        closed();
                    });
                });
            });
        });
    }

    /** A Drone Port broken while its chunk does not tick: the simulation must forget it at once. */
    private static void portBroken(MinecraftServer server, ServerLevel level, Site s) {
        all(level, s);
        ground(level, s);
        DronePortBlockEntity port = dronePort(level, s);
        if (port == null) {
            expect("broken port: a Drone Port goes down", false, "");
            closed();
            return;
        }
        BlockPos at = port.getBlockPos();
        port.companyId = AlwaysTests.company(server).id;
        port.items.setItem(PortContainer.OUT, PackageItem.containing(List.of(new ItemStack(Items.GOLD_INGOT, 2))));
        port.setChanged();
        later(server, 60, () -> {
            ring(level, s);
            later(server, 60, () -> {
                SimData.Node before = Factories.node(level, at);
                boolean sim = before != null && before.sim;
                level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                later(server, 5, () -> {
                    SimData.Node after = Factories.node(level, at);
                    expect("broken port: a Drone Port broken while simulated is forgotten at once", sim && after == null,
                        "simulated before " + sim + "; known after " + (after != null) + "; terminal " + state(level, at));
                    none(level, s);
                    closed();
                });
            });
        });
    }

    // ------------------------------------------------------------------------------------------------ belts

    /** Iron on its way along a simulated belt when both ends tick again must not vanish. */
    private static void transitHandover(MinecraftServer server, ServerLevel level, Site s) {
        all(level, s);
        ground(level, s);
        BlockPos anchor = null;
        for (int dx = 15; dx >= 0 && anchor == null; dx--) {
            BlockPos at = new BlockPos(s.x(0) + dx, Y + 1, s.z(1) + 4);
            boolean ok = true;
            for (BlockPos c : pairCells(at, Direction.EAST, 4)) if (c.getZ() >> 4 != s.cz + 1 || Math.abs((c.getX() >> 4) - s.cx) > 1) ok = false;
            if (ok) anchor = at;
        }
        Pair p = anchor == null ? null : pair(level, anchor, Direction.EAST, 4);
        if (p == null) {
            expect("handover: two Storage Containers and a belt go down", false, "");
            closed();
            return;
        }
        long start = fill(level, p.a, IRON, 256);
        AABB box = s.box();
        String key = SimData.key(level, p.a.getBlockPos());
        later(server, 40, () -> {
            Factories.recordNow(level, p.a);
            Factories.recordNow(level, p.b);
            report("handover: " + links(SimData.get(server), key) + " link(s) from the first container");
            none(level, s);
            until(server, () -> unloaded(level, s), 600, () -> {
                // a little simulated time: the belt between them has iron on it
                until(server, () -> carried(SimData.get(server), key) > 0, 300, () -> {
                    long onBelt = carried(SimData.get(server), key);
                    all(level, s);
                    later(server, 160, () -> {
                        long now = count(level, box, IRON);
                        expect("belt items across a handover: iron on a simulated belt when both ends tick again is delivered, not lost",
                            now == start, now + " against " + start + "; " + onBelt + " was on the simulated belt; left there now " + carried(SimData.get(server), key));
                        none(level, s);
                        closed();
                    });
                });
            });
        });
    }

    /** A pair along the row after F, A in one column, B in the next one east or west, as the case wants. */
    private static @Nullable Pair rowPair(ServerLevel level, Site s, Direction facing, int aCol, int bCol) {
        for (int dz = 2; dz < 12; dz++) for (int dx = -32; dx < 64; dx++) {
            BlockPos at = new BlockPos(s.x(0) + dx, Y + 1, s.z(1) + dz);
            List<BlockPos> a = cells("storage_container", at, facing);
            List<BlockPos> all = pairCells(at, facing, 4);
            if (a.isEmpty() || all.size() <= a.size()) continue;
            boolean ok = true;
            for (BlockPos c : a) if ((c.getX() >> 4) - s.cx != aCol || c.getZ() >> 4 != s.cz + 1) ok = false;
            List<BlockPos> b = all.subList(all.size() - a.size(), all.size());
            for (BlockPos c : b) if ((c.getX() >> 4) - s.cx != bCol || c.getZ() >> 4 != s.cz + 1) ok = false;
            if (ok) return pair(level, at, facing, 4);
        }
        return null;
    }

    /**
     * A belt from a ticking building to a simulated one is taken away while iron is on it in the simulation: that iron
     * must go back to where it came from.
     */
    private static void deadLink(MinecraftServer server, ServerLevel level, Site s) {
        all(level, s);
        ground(level, s);
        // A in the ticking column next to F, B one further east (loaded, not ticking): the belt runs east
        Pair p = rowPair(level, s, Direction.WEST, 1, 2);
        if (p == null) {
            expect("dead link: two Storage Containers and a belt go down", false, "");
            closed();
            return;
        }
        long start = fill(level, p.a, IRON, 128);
        AABB box = s.box();
        String key = SimData.key(level, p.a.getBlockPos());
        later(server, 40, () -> {
            Factories.recordNow(level, p.a);
            Factories.recordNow(level, p.b);
            ring(level, s);
        });
        until(server, () -> carried(SimData.get(server), key) > 3, 500, () -> {
            long onBelt = carried(SimData.get(server), key);
            SimData.Node b = Factories.node(level, p.b.getBlockPos());
            report("dead link: A " + state(level, p.a.getBlockPos()) + ", B " + state(level, p.b.getBlockPos()) + " simulated " + (b != null && b.sim) + "; " + onBelt + " iron on the simulated belt");
            // the belt is taken away
            for (BlockPos pos : BlockPos.betweenClosed(p.beltFrom, p.beltTo)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            later(server, 220, () -> {
                all(level, s);
                later(server, 120, () -> {
                    long now = count(level, box, IRON);
                    expect("dead link: iron on a belt that is no longer there goes back to its building", now == start, now + " against " + start);
                    none(level, s);
                    closed();
                });
            });
        });
    }

    /**
     * A simulated building's belt into a ticking building the simulation has not read yet (as a HUB never is): the link
     * and the iron on it must not be forgotten.
     */
    private static void unrecordedTarget(MinecraftServer server, ServerLevel level, Site s) {
        all(level, s);
        ground(level, s);
        // A two columns east (loaded, not ticking), B in the ticking column next to F: the belt runs west
        Pair p = rowPair(level, s, Direction.EAST, 2, 1);
        if (p == null) {
            expect("unrecorded target: two Storage Containers and a belt go down", false, "");
            closed();
            return;
        }
        long start = fill(level, p.a, IRON, 128);
        AABB box = s.box();
        String key = SimData.key(level, p.a.getBlockPos()), bKey = SimData.key(level, p.b.getBlockPos());
        later(server, 40, () -> {
            Factories.recordNow(level, p.a);
            Factories.recordNow(level, p.b);
            ring(level, s);
        });
        until(server, () -> carried(SimData.get(server), key) > 3, 500, () -> {
            // just before the next full check, B looks as a building the simulation never read
            long now = level.getGameTime();
            long wait = Factories.RECORD - 1 - (now % Factories.RECORD);
            later(server, (int) wait, () -> {
                SimData.Node b = SimData.get(server).node(bKey);
                if (b != null) {
                    b.building = "";
                    b.mode = "none";
                }
                later(server, 3, () -> {
                    SimData data = SimData.get(server);
                    boolean known = data.node(bKey) != null;
                    boolean linked = false;
                    for (SimData.Link l : data.stored.links) if (l.from.equals(key) && l.to.equals(bKey)) linked = true;
                    expect("unrecorded target: a ticking building not yet read keeps its belt from a simulated one", known && linked,
                        "known " + known + ", linked " + linked + "; B " + state(level, p.b.getBlockPos()));
                    all(level, s);
                    later(server, 160, () -> {
                        long n = count(level, box, IRON);
                        expect("unrecorded target: loaded again, nothing went missing on the way", n == start, n + " against " + start);
                        none(level, s);
                        closed();
                    });
                });
            });
        });
    }

    /** A belt's allowance over a long step is the step's own length, not a fixed two seconds. */
    private static void allowance(MinecraftServer server, ServerLevel level) {
        SimData data = new SimData();
        SimData.Node from = data.nodeOrNew(level, new BlockPos(30_000, 100, -30_000), "building");
        from.mode = "store";
        from.outCap = 100_000;
        from.out.put(Keys.of(IRON), 10_000L);
        from.sim = true;
        SimData.Link l = new SimData.Link();
        l.from = from.key();
        l.to = "nowhere";
        l.rate = 1;
        l.share = 1;
        l.cap = 100_000;
        long now = level.getGameTime();
        l.last = now - 200;
        Sim.pull(l, from, now);
        expect("allowance: a belt carrying one a tick takes 200 over a 200-tick step", l.carrying() == 200, l.carrying() + " taken");
        closed();
    }

    // ------------------------------------------------------------------------------------------------ power

    /**
     * A grid all of whose buildings are simulated, with an empty Biomass Burner, a charged Power Storage and an idle
     * Constructor: the Power Storage covers the Constructor's standby draw, and the fuse holds.
     */
    private static void battery(MinecraftServer server, ServerLevel level, Site s) {
        all(level, s);
        ground(level, s);
        Set<BlockPos> taken = new HashSet<>();
        int nx = s.cx, nz = s.cz + 2;
        ControllerBlockEntity con = put(level, "constructor", fit("constructor", Direction.EAST, nx, nz, taken), Direction.EAST);
        ControllerBlockEntity store = put(level, "power_storage", fit("power_storage", Direction.EAST, nx, nz, taken), Direction.EAST);
        ControllerBlockEntity burner = put(level, "biomass_burner", fit("biomass_burner", Direction.EAST, nx, nz, taken), Direction.EAST);
        boolean wired = con != null && store != null && burner != null
            && PowerTests.wire(PowerTests.connector(level, burner), PowerTests.connector(level, store))
            && PowerTests.wire(PowerTests.connector(level, store), PowerTests.connector(level, con));
        if (!wired) {
            expect("battery: a Constructor, a Power Storage and a Biomass Burner go down in one chunk, wired", false,
                (con == null ? "no Constructor " : "") + (store == null ? "no Power Storage " : "") + (burner == null ? "no burner" : ""));
            closed();
            return;
        }
        double charge = Sim.storageCap() / 2;
        store.energy = charge;
        store.setChanged();
        for (ControllerBlockEntity c : new ControllerBlockEntity[]{con, store, burner}) Factories.recordNow(level, c);
        BlockPos sp = store.getBlockPos(), bp = burner.getBlockPos(), cp = con.getBlockPos();
        later(server, 40, () -> {
            none(level, s);
            until(server, () -> unloaded(level, s), 600, () -> {
                SimData.Node first = Factories.node(level, sp);
                double atStart = first == null ? -1 : first.stored;
                later(server, 200, () -> {
                    SimData.Node st = Factories.node(level, sp), bn = Factories.node(level, bp), cn = Factories.node(level, cp);
                    boolean ok = st != null && st.sim && st.stored > 0 && bn != null && !bn.tripped && cn != null && cn.powered;
                    expect("battery: on a grid wholly unloaded the Power Storage is simulated and covers the Constructor's draw; the fuse holds", ok,
                        "storage " + (st == null ? "unknown" : (st.sim ? "simulated" : "not simulated") + ", " + Math.round(st.stored) + " from " + Math.round(atStart))
                            + "; burner " + (bn == null ? "unknown" : bn.tripped ? "fuse blown" : "fuse holds") + "; Constructor " + (cn == null ? "unknown" : cn.powered ? "powered" : "not powered"));
                    closed();
                });
            });
        });
    }

    /** Copies a node, so a step of it touches nothing real. */
    private static SimData.Node copy(SimData.Node n) {
        SimData.Node c = JsonSavedData.GSON.fromJson(JsonSavedData.GSON.toJson(n), SimData.Node.class);
        SimData.ensure(c);
        return c;
    }

    /**
     * The Nuclear Power Plant as the simulation runs it: it takes no rod when the waste would not fit, makes nothing
     * without water, and runs when it has both (its model read from a real one).
     */
    private static void nuclear(MinecraftServer server, ServerLevel level, Site s) {
        all(level, s);
        BuildingTests.platform(level, s.x(-1), s.z(1), s.x(3) - 1, s.z(4) - 1, Y, 20);
        ControllerBlockEntity plant = put(level, "nuclear_power_plant", new BlockPos(s.x(0) + 2, Y + 1, s.z(1) + 4), Direction.EAST);
        if (plant == null) {
            expect("nuclear: a Nuclear Power Plant goes down", false, "");
            closed();
            return;
        }
        String rod = "siftec:uranium_fuel_rod", waste = "siftec:uranium_waste", water = "fluid:minecraft:water";
        plant.input.setItem(0, new ItemStack(item(rod), 10));
        Factories.recordNow(level, plant);
        SimData.Node model = Factories.node(level, plant.getBlockPos());
        if (model == null) {
            expect("nuclear: the plant is recorded", false, "");
            closed();
            return;
        }
        long now = level.getGameTime();
        int room = BuildingPower.WASTE_ROOM;
        expect("nuclear: the recorded plant's output holds several rods' worth of waste, as the real one", model.outCap == room,
            model.outCap + " (" + room + " wanted)");
        // with water and room for the waste: it runs
        SimData.Node ok = copy(model);
        ok.sim = true;
        ok.simTime = now;
        ok.energy = 0;
        ok.tripped = false;
        ok.in.clear();
        ok.in.put(rod, 10L);
        ok.out.clear();
        ok.fin.clear();
        ok.fin.put(water, 100_000L);
        ok.outCap = room;
        Sim.power(List.of(ok), now + 400);
        expect("nuclear: with rods, water and room for the waste the plant gives power", ok.given > 0, Math.round(ok.given) + " SU-ticks");
        SimData.Node full = copy(ok);
        full.simTime = now;
        full.energy = 0;
        full.given = 0;
        full.in.clear();
        full.in.put(rod, 10L);
        full.out.clear();
        full.out.put(waste, (long) room);
        full.fin.clear();
        full.fin.put(water, 100_000L);
        Sim.power(List.of(full), now + 400);
        expect("nuclear: with its waste output full it takes no rod and spills no waste", Sim.get(full.in, rod) == 10 && Sim.total(full.out) <= room,
            Sim.get(full.in, rod) + " rods left, " + Sim.total(full.out) + " waste");
        // one rod's waste still waiting to be carried away: it takes its next rod all the same
        SimData.Node waiting = copy(ok);
        waiting.simTime = now;
        waiting.energy = 0;
        waiting.given = 0;
        waiting.in.clear();
        waiting.in.put(rod, 10L);
        waiting.out.clear();
        waiting.out.put(waste, 50L);
        waiting.fin.clear();
        waiting.fin.put(water, 100_000L);
        Sim.power(List.of(waiting), now + 400);
        expect("nuclear: with one rod's waste still in its output it takes its next rod and gives power", waiting.given > 0 && Sim.get(waiting.in, rod) < 10,
            Math.round(waiting.given) + " SU-ticks, " + Sim.get(waiting.in, rod) + " rods left, " + Sim.total(waiting.out) + " waste");
        SimData.Node dry = copy(ok);
        dry.simTime = now;
        dry.energy = 0;
        dry.given = 0;
        dry.in.clear();
        dry.in.put(rod, 10L);
        dry.out.clear();
        dry.fin.clear();
        Sim.power(List.of(dry), now + 400);
        expect("nuclear: with no water it makes nothing and keeps its rods", dry.given == 0 && Sim.get(dry.in, rod) == 10,
            Math.round(dry.given) + " SU-ticks, " + Sim.get(dry.in, rod) + " rods left");
        none(level, s);
        closed();
    }

    // ------------------------------------------------------------------------------------------------ chunk loads

    /**
     * Reading a building whose belt runs through a chunk that is not loaded must not load that chunk. Two chunks six
     * apart are force-loaded: the chunk three from each is not loaded, those either side of it are; a 20-block belt
     * starts at a Storage Container two chunks east of the first, crosses the unloaded chunk and ends in the loaded one
     * beyond (so the belt's controller, at either end, is loaded).
     */
    private static void tracer(MinecraftServer server, ServerLevel level, Site s) {
        for (int dx = 0; dx <= 6; dx++) {
            level.setChunkForced(s.cx + dx, s.cz, true);
            level.getChunk(s.cx + dx, s.cz);
        }
        BuildingTests.platform(level, s.x(1), s.z(0), s.x(6) - 1, s.z(1) - 1, Y, 14);
        int edge = s.x(3);
        ControllerBlockEntity a = null;
        BlockPos beltFrom = null, beltTo = null;
        for (Direction facing : new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH}) {
            for (int dx = 15; dx >= 0 && a == null; dx--) {
                BlockPos at = new BlockPos(s.x(2) + dx, Y + 1, s.z(0) + 6);
                Port out = port("storage_container", at, facing, PortBlock.Kind.ITEM_OUT);
                if (out == null || out.facing != Direction.EAST) break;
                // the belt: from the block east of the port to 20 blocks on, crossing all of chunk 3 into chunk 4
                if (out.pos.getX() != edge - 3) continue;
                boolean ok = true;
                for (BlockPos c : cells("storage_container", at, facing)) if (c.getX() >> 4 != s.cx + 2 || c.getZ() >> 4 != s.cz) ok = false;
                if (!ok) continue;
                a = put(level, "storage_container", at, facing);
                beltFrom = out.pos.east();
                beltTo = out.pos.east(20);
            }
            if (a != null) break;
        }
        if (a == null || beltFrom == null) {
            expect("tracer: a Storage Container with a belt through the next chunk goes down", false, "");
            closed();
            return;
        }
        AlwaysTests.belt(level, beltFrom, beltTo);
        ControllerBlockEntity building = a;
        long far = ChunkPos.pack(s.cx + 3, s.cz);
        BlockPos from = beltFrom, to = beltTo;
        later(server, 40, () -> {
            // F and the chunk six east stay: the building's chunk and the belt's far end are loaded, the middle is not
            for (int dx = 1; dx <= 5; dx++) level.setChunkForced(s.cx + dx, s.cz, false);
            until(server, () -> !level.hasChunk(s.cx + 3, s.cz), 600, () -> {
                boolean ready = !level.hasChunk(s.cx + 3, s.cz) && level.isLoaded(building.getBlockPos()) && level.isLoaded(to);
                watched = far;
                watchedLoads = 0;
                BlockEntity start = level.getBlockEntity(from);
                boolean ctrl = start instanceof BeltBlockEntity b && b.getControllerBE() != null;
                Factories.recordNow(level, building);
                int loads = watchedLoads;
                watched = Long.MIN_VALUE;
                expect("tracer: reading a building does not load a chunk its belt runs through", ready && ctrl && loads == 0,
                    (ready ? "" : "set-up not reached: ") + loads + " loads of the middle chunk; the belt's controller " + (ctrl ? "loaded" : "NOT loaded"));
                for (int dx = 0; dx <= 6; dx++) level.setChunkForced(s.cx + dx, s.cz, false);
                closed();
            });
        });
    }

    // ------------------------------------------------------------------------------------------------ vehicles

    /**
     * A simulated Truck on a route whose stop is at a Truck Station whose chunk ticks, while the route point (where the
     * Truck would be) does not: it still unloads there.
     */
    private static void truck(MinecraftServer server, ServerLevel level, Site s) {
        all(level, s);
        ground(level, s);
        Set<BlockPos> taken = new HashSet<>();
        ControllerBlockEntity station = put(level, "truck_station", fit("truck_station", Direction.NORTH, s.cx, s.cz + 1, taken), Direction.NORTH);
        if (station == null) {
            expect("truck: a Truck Station goes down", false, "");
            closed();
            return;
        }
        station.unload = true;
        station.setChanged();
        Factories.recordNow(level, station);
        ring(level, s);
        SimData data = SimData.get(server);
        SimData.Vehicle v = new SimData.Vehicle();
        v.uuid = UUID.randomUUID().toString();
        v.type = VehicleKind.TRUCK.id;
        v.dim = "minecraft:overworld";
        v.company = AlwaysTests.company(server).id;
        v.speed = VehicleKind.TRUCK.maxSpeed;
        v.mw = VehicleKind.TRUCK.mw;
        v.tank = VehicleKind.TRUCK.tankMj();
        v.fuel = v.tank;
        v.slots = VehicleKind.TRUCK.slots();
        v.cargo.add(new SimData.Slot(Keys.of(IRON), 64));
        v.cargo.add(new SimData.Slot(Keys.of(IRON), 64));
        // a small loop in the loaded, not ticking row, its second point the stop at the station
        int x = s.x(0) + 4, z = s.z(2) + 6;
        double[][] pts = {{x, Y + 1, z}, {x + 6, Y + 1, z}, {x + 6, Y + 1, z + 6}, {x, Y + 1, z + 6}};
        for (double[] p : pts) for (double c : p) v.route.add(c);
        v.stopAt.add(1);
        v.stopStation.add(station.getBlockPos().asLong());
        v.target = 1;
        v.x = x;
        v.y = Y + 1;
        v.z = z;
        v.simTime = level.getGameTime();
        data.stored.vehicles.add(v);
        data.setDirty();
        later(server, 600, () -> {
            SimData.Vehicle now = SimData.get(server).vehicle(v.uuid);
            long cargo = 0;
            if (now != null) for (SimData.Slot c : now.cargo) cargo += c.count;
            long there = AlwaysTests.held(level, station, IRON);
            expect("truck: at a stop whose Truck Station ticks while the route point does not, the Truck still unloads", there > 0 && cargo < 128,
                "station " + state(level, station.getBlockPos()) + " holds " + there + "; truck " + (now == null ? "not simulated" : "carries " + cargo + ", " + now.stopsMade + " stops"));
            SimData.get(server).stored.vehicles.removeIf(o -> o.uuid.equals(v.uuid));
            none(level, s);
            closed();
        });
    }

    // ------------------------------------------------------------------------------------------------ saving

    /** A company's quick save (a Sink paying, say) does not write the factory simulation: that goes with the world save. */
    private static void saving(MinecraftServer server, ServerLevel level) {
        SimData.get(server).setDirty();
        int before = SimData.encodes();
        Companies.save(server);
        later(server, 140, () -> {
            int writes = SimData.encodes() - before;
            expect("saving: a company's quick save does not write the factory simulation out of step with the chunks", writes == 0, writes + " writes");
            closed();
        });
    }
}
