package com.meakaandre.siftec.node;

import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.registry.ModBlocks;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Puts the node blocks into the world. A node is a low mound about 7 blocks across that follows the ground.
 * Each chunk places only its own columns of a mound, the first time it loads, so no neighbouring chunk is
 * ever forced to load or generate.
 */
public final class NodePlacer {
    /** Mound radius in blocks. */
    public static final int RADIUS = 3;
    private static final ArrayDeque<long[]> QUEUE = new ArrayDeque<>();

    private NodePlacer() {
    }

    public static void register() {
        // only note the chunk here: blocks are placed from the tick, when the chunk is fully in the world
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            if (!NodeMap.hasNodes(level)) return;
            ChunkPos pos = chunk.getPos();
            synchronized (QUEUE) {
                QUEUE.add(new long[]{pos.x(), pos.z()});
            }
        });
        ServerTickEvents.END_LEVEL_TICK.register(level -> {
            if (!NodeMap.hasNodes(level)) return;
            NodeMap.settle(level);
            int placed = 0;
            for (int i = 0; i < 512 && placed < 8; i++) {
                long[] next;
                synchronized (QUEUE) {
                    next = QUEUE.poll();
                }
                if (next == null) return;
                if (placeChunk(level, (int) next[0], (int) next[1])) placed++;
            }
        });
    }

    /** The nodes whose mound reaches into this chunk. */
    private static Set<Node> nodesTouching(ServerLevel level, int chunkX, int chunkZ) {
        Set<Node> nodes = new HashSet<>(2);
        int minX = (chunkX << 4) - RADIUS, maxX = (chunkX << 4) + 15 + RADIUS;
        int minZ = (chunkZ << 4) - RADIUS, maxZ = (chunkZ << 4) + 15 + RADIUS;
        // mounds never cross a cell edge, so the cells under the chunk's corners are the only candidates
        for (int cx = Math.floorDiv(minX, NodeMap.CELL); cx <= Math.floorDiv(maxX, NodeMap.CELL); cx++) {
            for (int cz = Math.floorDiv(minZ, NodeMap.CELL); cz <= Math.floorDiv(maxZ, NodeMap.CELL); cz++) {
                Optional<Node> node = NodeMap.inCell(level, cx, cz);
                if (node.isEmpty()) continue;
                Node n = node.get();
                if (n.x() >= minX && n.x() <= maxX && n.z() >= minZ && n.z() <= maxZ) nodes.add(n);
            }
        }
        return nodes;
    }

    /** Places the node blocks that belong in this chunk, once. True if it placed any. */
    public static boolean placeChunk(ServerLevel level, int chunkX, int chunkZ) {
        if (!NodeMap.ready(level) || !level.hasChunk(chunkX, chunkZ)) return false;
        Set<Node> nodes = nodesTouching(level, chunkX, chunkZ);
        if (nodes.isEmpty()) return false;
        NodeSavedData data = NodeSavedData.get(level.getServer());
        long key = ChunkPos.pack(chunkX, chunkZ);
        if (data.isDone(key)) return false;
        for (Node node : nodes) {
            placeColumns(level, node, chunkX, chunkZ);
        }
        data.markDone(key);
        return true;
    }

    private static void placeColumns(ServerLevel level, Node node, int chunkX, int chunkZ) {
        Block ore = ModBlocks.NODES.get(node.type()).get();
        BlockState oreState = ore.defaultBlockState();
        BlockState rock = ModBlocks.NODE_ROCK.get().defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                int x = node.x() + dx, z = node.z() + dz;
                if (x >> 4 != chunkX || z >> 4 != chunkZ) continue;
                double r = Math.sqrt(dx * dx + dz * dz);
                long h = hash(node.key(), dx, dz);
                // a ragged outline: the outermost ring is only partly filled
                if (r > RADIUS + 0.3 || r > RADIUS - 0.7 && (h & 3) == 0) continue;

                int y = ground(level, x, z, pos);
                if (y == Integer.MIN_VALUE) continue;
                boolean core = dx == 0 && dz == 0;
                boolean centre = r < 1.5;
                int roll = (int) ((h >>> 8) % 100);
                BlockState state = core ? oreState.setValue(NodeBlock.CORE, true)
                    : centre || roll < 45 ? oreState : rock;
                level.setBlock(pos.set(x, y, z), state, Block.UPDATE_CLIENTS);
                // lumps around the flat centre
                int top = y;
                if (!centre && roll >= 70) {
                    top = y + 1;
                    level.setBlock(pos.set(x, top, z), roll >= 85 ? oreState : rock, Block.UPDATE_CLIENTS);
                }
                // clear grass, flowers and snow sitting on the mound
                for (int up = 1; up <= 2; up++) {
                    BlockState above = level.getBlockState(pos.set(x, top + up, z));
                    if (!above.isAir() && above.canBeReplaced() && above.getFluidState().isEmpty()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    /** The top ground block of a column, skipping trees and plants. MIN_VALUE if the column is empty (void). */
    private static int ground(ServerLevel level, int x, int z, BlockPos.MutableBlockPos pos) {
        int y = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
        int min = level.getMinY();
        while (y > min) {
            BlockState state = level.getBlockState(pos.set(x, y, z));
            if (!state.isAir() && !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES) && !state.canBeReplaced()) {
                return y;
            }
            y--;
        }
        return Integer.MIN_VALUE;
    }

    private static long hash(long key, int dx, int dz) {
        long v = key * 0x9E3779B97F4A7C15L + dx * 31L + dz * 131L;
        v ^= v >>> 29;
        v *= 0xBF58476D1CE4E5B9L;
        v ^= v >>> 32;
        return v & Long.MAX_VALUE;
    }
}
