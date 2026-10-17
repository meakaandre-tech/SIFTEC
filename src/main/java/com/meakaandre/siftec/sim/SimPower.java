package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.building.BuildingIndex;
import com.meakaandre.siftec.power.PowerGrids;
import com.meakaandre.siftec.power.WireMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which simulated buildings share a power grid, read from the {@link WireMap} (no chunk is loaded for it). A grid that
 * also reaches a connector {@link PowerGrids} balances (one with a ticking connector somewhere) is "mixed": it is
 * balanced there, with the simulated members standing in through {@link com.meakaandre.siftec.power.PowerMemory}; every
 * other grid is balanced by the simulation ({@link Sim#power}), including grids that sit entirely in chunks that do not
 * tick.
 */
final class SimPower {
    record Grid(List<SimData.Node> members, boolean mixed) {
    }

    private SimPower() {
    }

    static List<Grid> grids(ServerLevel level, SimData data, List<SimData.Node> nodes) {
        WireMap map = WireMap.get(level);
        String dim = level.dimension().identifier().toString();
        LongOpenHashSet seen = new LongOpenHashSet();
        List<Grid> out = new ArrayList<>();
        for (SimData.Node start : nodes) {
            if (start.connectors.isEmpty() || seen.contains((long) start.connectors.getFirst())) continue;
            Map<String, SimData.Node> members = new LinkedHashMap<>();
            boolean mixed = false;
            Deque<Long> queue = new ArrayDeque<>();
            for (long c : start.connectors) if (seen.add(c)) queue.add(c);
            int guard = 0;
            while (!queue.isEmpty() && guard++ < 20_000) {
                long pos = queue.poll();
                if (PowerGrids.at(level, pos) != null) mixed = true;
                BlockPos at = BlockPos.of(pos);
                BlockPos controller = BuildingIndex.controllerOf(level, at);
                if (controller != null) {
                    SimData.Node n = data.node(SimData.key(dim, controller.asLong()));
                    if (n != null && n.sim) members.putIfAbsent(n.key(), n);
                }
                for (BlockPos next : map.links(level, at)) if (seen.add(next.asLong())) queue.add(next.asLong());
            }
            if (!members.isEmpty()) out.add(new Grid(new ArrayList<>(members.values()), mixed));
        }
        return out;
    }
}
