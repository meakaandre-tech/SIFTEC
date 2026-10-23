package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.power.GridStats;
import com.meakaandre.siftec.power.PowerGrids;
import com.meakaandre.siftec.power.WireMap;
import com.meakaandre.siftec.sim.SimData.Link;
import com.meakaandre.siftec.sim.SimData.Node;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Works out the groups of linked simulated buildings for a step, with their links, power grids and vehicles. */
final class FactoryGroups {
    /** Nodes in mixed grids (balanced by PowerGrids), by key, with the connector their grid is found by. */
    static final Map<String, Long> MIXED = new HashMap<>();
    /** The power grids worked out for the last step, kept while nothing changed. */
    static final Map<ServerLevel, List<SimPower.Grid>> GRIDS = new IdentityHashMap<>();
    static final Map<ServerLevel, String> GRIDS_FOR = new IdentityHashMap<>();

    private FactoryGroups() {
    }

    /** The groups of linked simulated buildings (with their links, power grids and vehicles) for a step. */
    static List<Sim.Group> groups(MinecraftServer s, SimData data) {
        Map<String, Map<String, Node>> byDim = new HashMap<>();
        for (Node n : data.stored.nodes.values()) byDim.computeIfAbsent(n.dim, k -> new LinkedHashMap<>()).put(n.key(), n);
        boolean real = data == SimData.get(s);
        if (real) MIXED.clear();
        List<Sim.Group> groups = new ArrayList<>();
        int vehicles = 0;
        for (Map.Entry<String, Map<String, Node>> dimEntry : byDim.entrySet()) {
            ServerLevel level = FactoryChunks.level(s, dimEntry.getKey());
            Map<String, Node> all = dimEntry.getValue();
            List<Node> sim = new ArrayList<>();
            for (Node n : all.values()) if (n.sim) sim.add(n);
            List<SimData.Vehicle> vs = new ArrayList<>();
            for (SimData.Vehicle v : data.stored.vehicles) if (v.dim.equals(dimEntry.getKey())) vs.add(v);
            vehicles += vs.size();
            if (sim.isEmpty() && vs.isEmpty()) continue;
            Union u = new Union();
            for (Node n : sim) u.find(n.key());
            // power: grids all simulated are balanced in the step; mixed ones by PowerGrids, read here
            List<SimPower.Grid> grids = level == null ? List.of() : grids(level, data, sim);
            Set<String> onGrid = new HashSet<>();
            for (SimPower.Grid g : grids) {
                for (Node n : g.members()) onGrid.add(n.key());
                if (g.mixed()) {
                    for (Node n : g.members()) mixedBefore(level, n, real);
                } else {
                    for (Node n : g.members()) n.mixed = false;
                    for (int i = 1; i < g.members().size(); i++) u.union(g.members().getFirst().key(), g.members().get(i).key());
                }
            }
            // a consumer on no grid has no power
            for (Node n : sim) if (!onGrid.contains(n.key()) && n.su > 0) n.powered = false;
            for (Link l : data.stored.links) {
                if (!l.dim.equals(dimEntry.getKey())) continue;
                Node from = all.get(l.from), to = all.get(l.to);
                if (from != null && to != null && from.sim && to.sim) u.union(l.from, l.to);
            }
            for (Node n : sim) {
                if (n.kind == NodeKind.DRONE_PORT && n.destination != null) {
                    Node far = all.get(SimData.key(n.dim, n.destination));
                    if (far != null && far.sim) u.union(n.key(), far.key());
                }
            }
            Map<String, String> vehicleRoot = new HashMap<>();
            for (SimData.Vehicle v : vs) {
                String root = null;
                for (long st : v.stopStation) {
                    Node station = all.get(SimData.key(v.dim, st));
                    if (station == null || !station.sim) continue;
                    if (root == null) root = station.key();
                    else u.union(root, station.key());
                }
                vehicleRoot.put(v.uuid, root == null ? "vehicle:" + v.uuid : root);
                u.find(root == null ? "vehicle:" + v.uuid : root);
            }
            Map<String, Sim.Group> byRoot = new LinkedHashMap<>();
            for (Node n : sim) {
                Sim.Group g = byRoot.computeIfAbsent(u.find(n.key()), k -> new Sim.Group(all));
                g.add(n);
            }
            for (SimPower.Grid grid : grids) {
                if (grid.mixed()) continue;
                Sim.Group g = byRoot.get(u.find(grid.members().getFirst().key()));
                if (g != null) g.grids.add(grid.members());
            }
            for (Link l : data.stored.links) {
                if (!l.dim.equals(dimEntry.getKey())) continue;
                Node from = all.get(l.from), to = all.get(l.to);
                Node anchor = from != null && from.sim ? from : to != null && to.sim ? to : null;
                if (anchor == null) continue;
                Sim.Group g = byRoot.get(u.find(anchor.key()));
                if (g != null) g.links.add(l);
            }
            for (SimData.Vehicle v : vs) {
                Sim.Group g = byRoot.computeIfAbsent(u.find(vehicleRoot.get(v.uuid)), k -> new Sim.Group(all));
                g.vehicles.add(v);
            }
            groups.addAll(byRoot.values());
        }
        Factories.lastVehicles = vehicles;
        return groups;
    }

    /** The power grids of the simulated buildings: worked out again when the wires or the simulated buildings change. */
    private static List<SimPower.Grid> grids(ServerLevel level, SimData data, List<Node> sim) {
        StringBuilder sig = new StringBuilder().append(WireMap.get(level).version()).append(':');
        for (Node n : sim) sig.append(n.pos).append(',');
        // PowerGrids' own grids change as chunks tick or stop: looked at again once a second anyway
        sig.append(':').append(level.getGameTime() / 20);
        String key = sig.toString();
        if (key.equals(GRIDS_FOR.get(level))) return GRIDS.get(level);
        List<SimPower.Grid> grids = SimPower.grids(level, data, sim);
        GRIDS.put(level, grids);
        GRIDS_FOR.put(level, key);
        return grids;
    }

    /**
     * A simulated member of a grid PowerGrids balances: whether that grid gave it power; a generator's fuel and a Power
     * Storage's charge and fuse are kept in step with its remembered stand-in ({@link FactoryResults#syncMemory}).
     */
    private static void mixedBefore(@Nullable ServerLevel level, Node n, boolean real) {
        if (level == null) return;
        if (real) MIXED.put(n.key(), n.connectors.isEmpty() ? n.pos : n.connectors.getFirst());
        PowerGrids.Grid grid = n.connectors.isEmpty() ? null : PowerGrids.at(level, n.connectors.getFirst());
        GridStats stats = grid == null ? null : grid.stats;
        if (n.su > 0) n.powered = stats != null && stats.powered && !stats.tripped;
        n.mixed = true;
        if (real) FactoryResults.syncMemory(level.getServer(), n, true);
    }

    /** Union-find over node keys. */
    private static final class Union {
        private final Map<String, String> parent = new HashMap<>();

        String find(String k) {
            String p = parent.get(k);
            if (p == null) {
                parent.put(k, k);
                return k;
            }
            if (p.equals(k)) return k;
            String r = find(p);
            parent.put(k, r);
            return r;
        }

        void union(String a, String b) {
            String ra = find(a), rb = find(b);
            if (!ra.equals(rb)) parent.put(ra, rb);
        }
    }
}
