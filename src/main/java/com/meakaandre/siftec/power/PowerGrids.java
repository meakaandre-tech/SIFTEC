package com.meakaandre.siftec.power;

import com.meakaandre.siftec.Siftec;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.antarcticgardens.cna.content.electricity.connector.AbstractElectricalConnector;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The pack's power grids, balanced Satisfactory's way on top of Create: New Age's wires.
 * <p>
 * A grid is every New Age connector joined by wires, loaded or not: the {@link WireMap} knows the wires of the ones
 * that are not. Its members are what its connectors stand on ({@link Members}): Build Gun buildings, the HUB's
 * Biomass Burners, and any New Age block a player wired up themselves. A member that is not loaded keeps its part in
 * the grid as it last was ({@link PowerMemory}), so a grid runs the same wherever the players are, as in Satisfactory. New Age's own way of passing energy along
 * wires (each source pushing down the shortest path, within each wire's limit) is switched off; instead, every tick:
 * <ol>
 * <li>the generators' capacity is added up (an Alien Power Augmenter raises it by 10%, or 30% fed);</li>
 * <li>if the consumers ask for no more than that, they all run, what is spare charges the Power Storages (each up to
 * its rate) and then fills any New Age block that takes energy; the generators give, and burn fuel for, only what is
 * used;</li>
 * <li>if they ask for more, the Power Storages cover the difference; if they cannot, the fuse trips: everything on the
 * grid stops and stays stopped until the fuse is reset at a generator or a Power Storage.</li>
 * </ol>
 * All of it in SU ({@link Power}).
 */
public final class PowerGrids {
    private static final Map<ServerLevel, Set<AbstractElectricalConnector>> LOADED = new IdentityHashMap<>();
    private static final Map<ServerLevel, Built> BUILT = new IdentityHashMap<>();
    /** Goes up whenever wires, connectors or buildings change: the grids are worked out again on the next tick. */
    private static int topology;
    /** How often the grids are worked out again anyway (members whose own state changed: a building placed, a motor set). */
    private static final int REBUILD_TICKS = 20;

    private PowerGrids() {
    }

    /** One grid as worked out: its connectors and members. */
    public static final class Grid {
        public final List<AbstractElectricalConnector> connectors = new ArrayList<>();
        public final List<PowerMember> members = new ArrayList<>();
        public final GridStats stats = new GridStats();
        /** FE that New Age sources pushed into a connector of this grid, to be given out next tick. */
        final Inbox inbox = new Inbox();
        /** SU the grid could still take last tick from what is pushed in (its spare demand and storage room). */
        double room;
        /** The memory of each real member, kept up to date every tick ({@link PowerMemory}). */
        final Map<PowerMember, PowerMemory.Remembered> memory = new IdentityHashMap<>();
        /** What each member offered, asked for and boosted by on the last tick, in the order of {@link #members}. */
        double[] caps = new double[0], demands = new double[0], boosts = new double[0];
    }

    private static final class Built {
        int topology = -1;
        long tick;
        final List<Grid> grids = new ArrayList<>();
        final Map<AbstractElectricalConnector, Grid> byConnector = new IdentityHashMap<>();
        /** Every connector position a grid reaches (loaded or not), and its grid. */
        final Long2ObjectOpenHashMap<Grid> at = new Long2ObjectOpenHashMap<>();
    }

    public static void register() {
        ServerTickEvents.END_LEVEL_TICK.register(PowerGrids::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LOADED.clear();
            BUILT.clear();
            Poles.clear();
        });
    }

    public static void dirty() {
        topology++;
    }

    /** A connector ticked: it is on the grid. The first time, it agrees its wires with the {@link WireMap}. */
    public static void seen(AbstractElectricalConnector connector) {
        if (!(connector.getLevel() instanceof ServerLevel level) || connector.isRemoved()) return;
        if (LOADED.computeIfAbsent(level, k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(connector)) {
            Wires.joinMap(level, connector);
            dirty();
        }
    }

    public static void gone(AbstractElectricalConnector connector) {
        if (!(connector.getLevel() instanceof ServerLevel level)) return;
        Set<AbstractElectricalConnector> set = LOADED.get(level);
        if (set != null && set.remove(connector)) dirty();
    }

    // ------------------------------------------------------------------------------------------------ the grids

    private static Built built(ServerLevel level) {
        Built b = BUILT.computeIfAbsent(level, k -> new Built());
        if (b.topology != topology || level.getGameTime() - b.tick >= REBUILD_TICKS || level.getGameTime() < b.tick) rebuild(level, b);
        return b;
    }

    /**
     * Works the grids out again. A grid is every position joined by wires, as the live connectors hold them and as the
     * {@link WireMap} remembers them, so it reaches through connectors that are not loaded and through loaded ones
     * that do not tick. A connector that is loaded (ticking or not) brings its real member; one that is not loaded
     * brings what {@link PowerMemory} remembers of its member, as does a loaded connector whose member's own chunk is
     * not loaded. So a grid never loses a generator, a Power Storage or a consumer because part of it stopped ticking.
     */
    private static void rebuild(ServerLevel level, Built b) {
        Map<AbstractElectricalConnector, Grid> old = new IdentityHashMap<>(b.byConnector);
        b.grids.clear();
        b.byConnector.clear();
        b.at.clear();
        b.topology = topology;
        b.tick = level.getGameTime();
        Set<AbstractElectricalConnector> loaded = LOADED.getOrDefault(level, Set.of());
        Map<BlockPos, AbstractElectricalConnector> at = new HashMap<>();
        for (var it = loaded.iterator(); it.hasNext(); ) {
            AbstractElectricalConnector c = it.next();
            // a connector whose chunk stopped ticking leaves: a grid with no ticking connector is balanced by the factory
            // simulation (work package 17); it joins again when it ticks
            if (c.isRemoved() || c.getLevel() != level || !level.isLoaded(c.getBlockPos()) || !level.shouldTickBlocksAt(c.getBlockPos())
                || level.getBlockEntity(c.getBlockPos()) != c) {
                it.remove();
                continue;
            }
            at.put(c.getBlockPos(), c);
        }
        WireMap map = WireMap.get(level);
        PowerMemory memory = PowerMemory.get(level);
        var remembered = memory.byConnector(level);
        Set<BlockPos> done = new HashSet<>();
        for (AbstractElectricalConnector first : List.copyOf(at.values())) {
            if (done.contains(first.getBlockPos())) continue;
            Grid grid = new Grid();
            List<BlockPos> away = new ArrayList<>();
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(first.getBlockPos());
            done.add(first.getBlockPos());
            while (!queue.isEmpty()) {
                BlockPos pos = queue.poll();
                AbstractElectricalConnector c = live(level, at, pos);
                Set<BlockPos> ends = map.links(level, pos);
                b.at.put(pos.asLong(), grid);
                if (c != null) {
                    grid.connectors.add(c);
                    b.byConnector.put(c, grid);
                    ends.addAll(c.getConnectorPositions().keySet());
                } else {
                    away.add(pos);
                }
                for (BlockPos end : ends) if (done.add(end.immutable())) queue.add(end.immutable());
            }
            Map<Object, PowerMember> members = new LinkedHashMap<>();
            for (AbstractElectricalConnector c : grid.connectors) {
                BlockPos key = Members.resolve(level, c, members);
                PowerMember real = key == null ? null : members.get(key);
                for (PowerMemory.Remembered r : listOr(remembered.get(c.getBlockPos().asLong()))) {
                    if (key == null || r.key != key.asLong()) memory.drop(level, r);
                }
                if (key == null) continue;
                if (real == null) {
                    // its own block's chunk is not loaded: it stands in for it
                    PowerMemory.Remembered r = memory.away(level, key);
                    if (r != null) members.putIfAbsent(key, r);
                } else {
                    grid.memory.put(real, memory.track(level, c.getBlockPos(), key, real));
                }
            }
            for (BlockPos pos : away) {
                for (PowerMemory.Remembered r : listOr(remembered.get(pos.asLong()))) members.putIfAbsent(BlockPos.of(r.key), PowerMemory.standIn(r));
            }
            grid.members.addAll(members.values());
            // a grid that keeps its connectors keeps what was pushed into it
            Grid before = old.get(first);
            if (before != null) grid.inbox.fe = before.inbox.fe;
            b.grids.add(grid);
        }
    }

    private static List<PowerMemory.Remembered> listOr(@Nullable List<PowerMemory.Remembered> list) {
        return list == null ? List.of() : list;
    }

    /** The connector at a position if it is loaded (whether it ticks or not), without loading anything. */
    private static @Nullable AbstractElectricalConnector live(ServerLevel level, Map<BlockPos, AbstractElectricalConnector> at, BlockPos pos) {
        AbstractElectricalConnector c = at.get(pos);
        if (c != null) return c;
        if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof AbstractElectricalConnector found) || found.isRemoved()) return null;
        at.put(pos.immutable(), found);
        return found;
    }

    /** True if a connector or member of the grid went away since it was worked out (broken, or its chunk unloaded). */
    private static boolean stale(Grid grid) {
        boolean ticking = grid.connectors.isEmpty();
        for (AbstractElectricalConnector c : grid.connectors) {
            if (c.isRemoved()) return true;
            if (!ticking && c.getLevel() instanceof ServerLevel l && l.shouldTickBlocksAt(c.getBlockPos())) ticking = true;
        }
        // none of its connectors ticks any more: the simulation takes the grid over
        if (!ticking) return true;
        for (PowerMember m : grid.members) if (m.gone()) return true;
        return false;
    }

    private static void tick(ServerLevel level) {
        if (!LOADED.containsKey(level) || LOADED.get(level).isEmpty() && !BUILT.containsKey(level)) return;
        Built b = built(level);
        // a chunk that unloaded this tick: its part of the grid is remembered before the grid is balanced without it
        for (Grid grid : b.grids) {
            if (stale(grid)) {
                rebuild(level, b);
                break;
            }
        }
        Set<BlockPos> live = new HashSet<>();
        for (Grid grid : b.grids) {
            try {
                solve(grid);
                remember(grid);
            } catch (RuntimeException e) {
                Siftec.LOGGER.error("SIFTEC: power grid at {} failed to balance", grid.connectors.isEmpty() ? "?" : grid.connectors.getFirst().getBlockPos(), e);
            }
            if (grid.stats.powered && grid.stats.produced > 0) for (AbstractElectricalConnector c : grid.connectors) live.add(c.getBlockPos());
        }
        if (level.getGameTime() % 20 == 0) Poles.set(level, live);
    }

    /** Each real member's part in this tick, kept in case its chunk unloads before the next. */
    private static void remember(Grid grid) {
        if (grid.memory.isEmpty() || grid.caps.length != grid.members.size()) return;
        for (int i = 0; i < grid.members.size(); i++) {
            PowerMember p = grid.members.get(i);
            PowerMemory.Remembered r = grid.memory.get(p);
            if (r != null) r.update(p, grid.caps[i], grid.demands[i], grid.boosts[i]);
        }
    }

    /** Balances one grid for this tick (see the class comment). */
    static void solve(Grid grid) {
        GridStats s = grid.stats;
        List<PowerMember> m = grid.members;
        double boost = 0, boosted = 0, flat = 0, demand = 0, stored = 0, storeCap = 0, chargeWant = 0, sinkWant = 0;
        int gens = 0, uses = 0, stores = 0;
        boolean tripped = false, fuse = false, starting = false;
        // each generator is asked once a tick: asking takes its next fuel when it runs low
        double[] caps = new double[m.size()], demands = new double[m.size()], boosts = new double[m.size()];
        grid.caps = caps;
        grid.demands = demands;
        grid.boosts = boosts;
        for (int i = 0; i < m.size(); i++) {
            PowerMember p = m.get(i);
            double bo = boosts[i] = p.boost();
            boost += bo;
            double c = caps[i] = p.capacity();
            if (c > 0 || p.holdsFuse() && p.storeCapacity() <= 0) gens++;
            if (p.boostable()) boosted += c;
            else flat += c;
            double d = demands[i] = p.demand();
            if (d > 0) uses++;
            demand += d;
            if (p.storeCapacity() > 0) {
                stores++;
                stored += p.stored();
                storeCap += p.storeCapacity();
                chargeWant += Math.max(0, Math.min(p.chargeRate(), p.storeCapacity() - p.stored()));
            }
            sinkWant += Math.max(0, p.sink());
            starting |= p.starting();
            if (p.holdsFuse()) {
                fuse = true;
                tripped |= p.tripped();
            }
        }
        // what New Age blocks pushed into the grid last tick (carbon brushes and the like)
        double pushed = Power.suOfFe(grid.inbox.fe);
        grid.inbox.fe = 0;
        double own = boosted * (1 + boost) + flat;
        double capacity = own + pushed;
        s.connectors = grid.connectors.size();
        s.capacity = capacity;
        s.demand = demand;
        s.boost = boost;
        s.generators = gens;
        s.consumers = uses;
        s.stores = stores;
        s.storeCapacity = storeCap;
        s.unpowered = false;
        double used = 0, charge = 0, sunk = 0;
        boolean on;
        if (tripped) {
            on = false;
        } else if (demand <= capacity) {
            on = true;
            double spare = capacity - demand;
            // spare power charges the Power Storages, each up to its rate, shared by how much each still wants
            charge = Math.min(spare, chargeWant);
            if (charge > 0) for (PowerMember p : m) {
                if (p.storeCapacity() <= 0) continue;
                double w = Math.max(0, Math.min(p.chargeRate(), p.storeCapacity() - p.stored()));
                if (w > 0) p.store(charge * w / chargeWant);
            }
            spare -= charge;
            // then any New Age block that takes energy
            sunk = Math.min(spare, sinkWant);
            if (sunk > 0) for (PowerMember p : m) {
                double w = Math.max(0, p.sink());
                if (w > 0) p.sunk(sunk * w / sinkWant);
            }
            used = demand + charge + sunk;
        } else {
            double shortBy = demand - capacity;
            if (stored > 0 && stored >= shortBy) {
                on = true;
                for (PowerMember p : m) {
                    if (p.storeCapacity() <= 0 || p.stored() <= 0) continue;
                    p.store(-shortBy * p.stored() / stored);
                }
                used = capacity;
                charge = -shortBy;
            } else if (fuse && !starting) {
                // more than the grid can make or store: the fuse blows, as in Satisfactory (not while a generator's
                // machines are still getting going: until then the grid just has no power)
                on = false;
                tripped = true;
                for (PowerMember p : m) if (p.holdsFuse()) p.trip(true);
            } else {
                // nothing to trip (no generator or storage of the pack's on it): it simply has no power
                on = false;
                s.unpowered = demand > 0;
            }
        }
        // the generators give what is used, each its share of its own capacity, and burn fuel for that much
        double load = capacity <= 0 ? 0 : Math.min(1, used / capacity);
        for (int i = 0; i < m.size(); i++) {
            if (caps[i] > 0) m.get(i).generated(caps[i] * load);
            else if (m.get(i).holdsFuse()) m.get(i).generated(0);
        }
        // what New Age sources may push in for next tick: what the grid would use beyond its own generators
        grid.room = tripped ? 0 : Math.max(0, demand + chargeWant + sinkWant - own);
        s.tripped = tripped;
        s.stored = stored + charge;
        s.produced = used;
        s.powered = on;
        for (PowerMember p : m) {
            if (p.demand() > 0) p.supplied(on);
            p.grid(s);
        }
    }

    // ------------------------------------------------------------------------------------------------ for others

    /**
     * The grid a connector position belongs to as last worked out (loaded or not), or null when no grid with a ticking
     * connector reaches it: such a grid is balanced by the factory simulation instead ({@code sim.SimPower}).
     */
    public static @Nullable Grid at(ServerLevel level, long connector) {
        Built b = BUILT.get(level);
        return b == null ? null : b.at.get(connector);
    }

    /** The grid a connector is on, as last worked out. */
    public static @Nullable Grid of(AbstractElectricalConnector connector) {
        if (!(connector.getLevel() instanceof ServerLevel level)) return null;
        Built b = BUILT.get(level);
        return b == null ? null : b.byConnector.get(connector);
    }

    /** The grid a member is on (worked out now if need be), or null if it is on none. */
    public static @Nullable Grid of(ServerLevel level, PowerMember member) {
        Built b = built(level);
        for (Grid g : b.grids) if (g.members.contains(member)) return g;
        return null;
    }

    /** Every grid of a level, worked out now if need be. */
    public static List<Grid> grids(ServerLevel level) {
        return List.copyOf(built(level).grids);
    }

    /** Resets a blown fuse: every generator and Power Storage on the member's grid. True if one was blown. */
    public static boolean reset(ServerLevel level, PowerMember member) {
        Grid grid = of(level, member);
        List<PowerMember> all = grid == null ? List.of(member) : grid.members;
        boolean was = false;
        for (PowerMember p : all) {
            if (p.holdsFuse() && p.tripped()) {
                was = true;
                p.trip(false);
            }
        }
        if (grid != null) grid.stats.tripped = false;
        return was;
    }

    /**
     * FE a New Age source pushes into a connector (its {@code NetworkEnergyStorage}): taken up to what the grid could
     * use, and given out with the rest of the grid's power next tick.
     */
    public static long pushed(AbstractElectricalConnector connector, long fe, TransactionContext txn) {
        Grid grid = of(connector);
        if (grid == null || grid.stats.tripped || fe <= 0) return 0;
        long room = (long) Math.floor(Power.feOfSu(grid.room) - grid.inbox.fe);
        long take = Math.max(0, Math.min(fe, room));
        if (take > 0) grid.inbox.add(take, txn);
        return take;
    }

    /** What pushed energy a grid holds for next tick, undone with the transaction that put it there. */
    static final class Inbox extends SnapshotParticipant<Long> {
        long fe;

        void add(long amount, TransactionContext txn) {
            updateSnapshots(txn);
            fe += amount;
        }

        @Override
        protected Long createSnapshot() {
            return fe;
        }

        @Override
        protected void readSnapshot(Long snapshot) {
            fe = snapshot;
        }
    }
}
