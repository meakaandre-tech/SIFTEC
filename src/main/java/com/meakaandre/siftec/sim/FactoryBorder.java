package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.sim.SimData.Link;
import com.meakaandre.siftec.sim.SimData.Node;
import com.meakaandre.siftec.util.Drops;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Map;

/** What crosses between ticking and simulated buildings on belts, and where what is on a forgotten belt goes. */
final class FactoryBorder {
    private FactoryBorder() {
    }

    /**
     * Forgets a node; what the simulation had on the belts to or from it goes to the building at the other end (or is
     * dropped where the belt was), rather than vanishing with them.
     */
    static void forget(MinecraftServer s, SimData data, String key) {
        for (Link l : new ArrayList<>(data.stored.links)) {
            if (l.from.equals(key) || l.to.equals(key)) flush(s, data, l, key);
        }
        data.remove(key);
    }

    /**
     * Empties a link: what is on it goes into the building at its end, or else back into the one it came from, or else is
     * dropped where it was going (a node that is gone, {@code gone}, takes nothing). Into the world where that building
     * runs for real, into the simulation's record where it does not.
     */
    static void flush(MinecraftServer s, SimData data, Link l, @Nullable String gone) {
        if (l.transit.isEmpty()) return;
        ServerLevel level = FactoryChunks.level(s, l.dim);
        Node to = l.to.equals(gone) ? null : data.node(l.to), from = l.from.equals(gone) ? null : data.node(l.from);
        for (SimData.Transit t : l.transit) {
            long left = t.n;
            if (to != null) left -= give(level, to, t.key, left, l.fluid, true);
            if (left > 0 && from != null) left -= give(level, from, t.key, left, l.fluid, false);
            if (left > 0 && !l.fluid && level != null) {
                BlockPos at = BlockPos.of(to == null ? l.fromPort : l.toPort);
                if (level.isLoaded(at)) {
                    drop(level, at, t.key, left);
                    left = 0;
                }
            }
            t.n = left;
        }
        // what had nowhere to go yet stays on the belt (a link that is being forgotten goes with its node)
        l.transit.removeIf(t -> t.n <= 0);
        if (gone != null && !l.transit.isEmpty()) {
            for (SimData.Transit t : l.transit) Siftec.LOGGER.info("SIFTEC: {} {} on a belt between {} and {} had nowhere to go", t.n, t.key, l.from, l.to);
        }
        data.setDirty();
    }

    /** Puts things into a node: at its input ({@code asInput}) or back where it keeps what it made. Returns how many went in. */
    private static long give(@Nullable ServerLevel level, Node n, String key, long count, boolean fluid, boolean asInput) {
        if (n.sim) {
            long take = asInput ? Math.min(count, SimBelts.room(n, key, fluid)) : count;
            if (take <= 0) return 0;
            if (asInput) SimBelts.put(n, key, take, fluid);
            else Sim.add(fluid ? n.fout : n.out, key, take);
            return take;
        }
        if (level == null || !FactoryChunks.loaded(level, n)) return 0;
        return asInput ? Buffers.insert(level, n, key, count, fluid) : Buffers.putBack(level, n, key, count, fluid);
    }

    static void drop(ServerLevel level, BlockPos at, String key, long count) {
        int max = Math.max(1, Keys.max(key));
        while (count > 0) {
            int c = (int) Math.min(count, max);
            ItemStack stack = Keys.stack(key, c);
            if (stack.isEmpty()) return;
            Drops.at(level, at, Buffers.DROP_HEIGHT, stack);
            count -= c;
        }
    }

    /**
     * What crosses between ticking and simulated buildings, on the server thread: a belt from a ticking building to a
     * simulated one takes from the ticking one's output as fast as it carries.
     */
    static void boundary(MinecraftServer s, SimData data, long now) {
        for (Link l : data.stored.links) {
            Node from = data.node(l.from), to = data.node(l.to);
            if (from == null || to == null || from.sim || !to.sim) continue;
            ServerLevel level = FactoryChunks.level(s, l.dim);
            if (level == null || !FactoryChunks.ticking(level, from, Long.MIN_VALUE) || !FactoryChunks.loaded(level, from)) continue;
            long dt = l.last == Long.MIN_VALUE ? 0 : Math.max(0, now - l.last);
            l.last = now;
            l.allowance = SimBelts.allow(l, dt);
            long most = Math.min((long) Math.floor(l.allowance + Sim.FLOOR_EPSILON), Math.max(0, l.cap - l.carrying()));
            if (most <= 0) continue;
            Map<String, Long> got = Buffers.extract(level, from, most, l.fluid);
            for (Map.Entry<String, Long> e : got.entrySet()) {
                l.transit.add(new SimData.Transit(now + l.delay, e.getKey(), e.getValue()));
                l.allowance -= e.getValue();
            }
        }
    }
}
