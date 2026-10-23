package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.drone.DronePortBlockEntity;
import com.meakaandre.siftec.sim.SimData.Node;
import com.meakaandre.siftec.util.Chunks;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The chunks a node stands in, whether they tick, and reading nodes as their chunks stop ticking. */
final class FactoryChunks {
    /** Node keys by dimension and chunk, for what to read when a chunk stops ticking; rebuilt when nodes change. */
    static final Map<String, Map<Long, List<String>>> BY_CHUNK = new HashMap<>();
    static @Nullable SimData byChunkFor;
    private static int byChunkVersion = -1;

    static final Map<String, ServerLevel> LEVELS = new HashMap<>();

    private FactoryChunks() {
    }

    /** Every chunk a node stands in (its own, if it has not been recorded with more). */
    static List<Long> chunks(Node n) {
        return n.chunks.isEmpty() ? List.of(ChunkPos.pack(n.blockPos())) : n.chunks;
    }

    /** True if any chunk the node stands in ticks (but {@code except}): the building runs for real. */
    static boolean ticking(ServerLevel level, Node n, long except) {
        for (long c : chunks(n)) {
            if (c == except) continue;
            if (Chunks.loaded(level, c) && level.shouldTickBlocksAt(c)) return true;
        }
        return false;
    }

    /** True if every chunk the node stands in is loaded: only then can the catch-up write into it. */
    static boolean loaded(ServerLevel level, Node n) {
        for (long c : chunks(n)) if (!Chunks.loaded(level, c)) return false;
        return true;
    }

    private static String dim(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    private static Map<Long, List<String>> byChunk(SimData data, String dim) {
        if (byChunkFor != data || byChunkVersion != data.version) {
            BY_CHUNK.clear();
            for (Node n : data.stored.nodes.values()) {
                Map<Long, List<String>> m = BY_CHUNK.computeIfAbsent(n.dim, k -> new HashMap<>());
                for (long c : chunks(n)) m.computeIfAbsent(c, k -> new ArrayList<>(2)).add(n.key());
            }
            byChunkFor = data;
            byChunkVersion = data.version;
        }
        return BY_CHUNK.getOrDefault(dim, Map.of());
    }

    /** Whether the simulation runs a node at all (one with nothing to simulate is left alone while its chunk does not tick). */
    static boolean simulates(Node n) {
        return n.mode != NodeMode.NONE || n.su > 0 || n.kind == NodeKind.DRONE_PORT;
    }

    /**
     * A chunk stopped ticking: each node standing in it whose every chunk has now stopped is read at once, while its block
     * entities are still there (a chunk leaving altogether is read from itself, never through the level, which would load
     * it again), and the simulation takes it over from that read, from now.
     */
    static void chunkStopped(ServerLevel level, LevelChunk chunk, boolean stillLoaded) {
        MinecraftServer s = Factories.server;
        if (s == null || Factories.stopping || s != level.getServer()) return;
        SimData data = SimData.get(s);
        long at = chunk.getPos().pack();
        List<String> keys = byChunk(data, dim(level)).get(at);
        if (keys == null) return;
        long now = level.getGameTime();
        Buffers.Reach reach = stillLoaded ? Buffers.level(level) : Buffers.leaving(level, chunk);
        for (String k : keys) {
            Node n = data.node(k);
            if (n == null || n.sim || !simulates(n)) continue;
            if (ticking(level, n, at)) continue;
            // the worker never reads a node it does not simulate, so this is safe while a step runs; a node read here
            // before (it stopped, ticked and stopped again during a slow step) is read again. Only a read that worked
            // parks it: otherwise the simulation checks it again (and forgets it if it is gone) when it takes it over
            if (present(level, reach, n) && FactoryRecording.refresh(level, reach, n)) {
                n.parkedAt = now;
                data.setDirty();
            } else {
                n.parkedAt = Long.MIN_VALUE;
            }
        }
    }

    /** The server is stopping: every node still running for real is read and handed to the simulation. */
    static void parkAll(MinecraftServer s) {
        SimData data = SimData.get(s);
        long now = s.overworld().getGameTime();
        for (Node n : data.stored.nodes.values()) {
            if (n.sim || !simulates(n)) continue;
            ServerLevel level = level(s, n.dim);
            if (level == null || !ticking(level, n, Long.MIN_VALUE) || !present(level, Buffers.level(level), n)) continue;
            FactoryRecording.refresh(level, n);
            n.parkedAt = Long.MIN_VALUE;
            FactoryCatchUp.start(level, n, now);
        }
        data.setDirty();
    }

    static @Nullable ServerLevel level(MinecraftServer s, String dim) {
        ServerLevel known = LEVELS.get(dim);
        if (known != null && known.getServer() == s) return known;
        for (ServerLevel level : s.getAllLevels()) {
            if (level.dimension().identifier().toString().equals(dim)) {
                LEVELS.put(dim, level);
                return level;
            }
        }
        return null;
    }

    /**
     * True if what the node stands for is still there (its chunk is loaded). A building the simulation has not read yet
     * (one a belt runs into, or one it never simulates, as the HUB) is there while any controller is.
     */
    static boolean present(ServerLevel level, Buffers.Reach reach, Node n) {
        BlockPos pos = n.blockPos();
        if (!reach.has(pos)) return false;
        BlockEntity be = reach.be(pos);
        return switch (n.kind) {
            case DRONE_PORT -> be instanceof DronePortBlockEntity;
            case INVENTORY -> ItemStorage.SIDED.find(level, pos, reach.state(pos), be, null) != null;
            default -> be instanceof ControllerBlockEntity c ? n.building.isEmpty() || c.buildingId.equals(n.building)
                : n.building.isEmpty() && n.mode == NodeMode.NONE && be != null;
        };
    }
}
