package com.meakaandre.siftec.node;

import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.registry.ModBlocks;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Puts the node blocks into the world. A node is a low mound about 7 blocks across.
 * Each chunk places only its own columns of a mound, the first time it loads, so no neighbouring chunk is
 * ever forced to load or generate. Surface mounds follow the ground column by column. Cave and Nether
 * mounds are level: their height is chosen when the chunk holding the middle of the node loads.
 */
public final class NodePlacer {
    /** Mound radius in blocks. */
    public static final int RADIUS = 3;

    private record Queued(ResourceKey<Level> dimension, int x, int z) {
    }

    private static final ArrayDeque<Queued> QUEUE = new ArrayDeque<>();

    private NodePlacer() {
    }

    public static void register() {
        // only note the chunk here: blocks are placed from the tick, when the chunk is fully in the world
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            if (!NodeMap.hasNodes(level)) return;
            ChunkPos pos = chunk.getPos();
            synchronized (QUEUE) {
                QUEUE.add(new Queued(level.dimension(), pos.x(), pos.z()));
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ServerLevel overworld = server.overworld();
            NodeMap.settle(overworld);
            int placed = 0;
            for (int i = 0; i < 512 && placed < 8; i++) {
                Queued next;
                synchronized (QUEUE) {
                    next = QUEUE.poll();
                }
                if (next == null) return;
                ServerLevel level = server.getLevel(next.dimension());
                if (level == null) continue;
                if (placeChunk(level, next.x(), next.z())) placed++;
                if (NodeMap.ready(level) && level.hasChunk(next.x(), next.z())) com.meakaandre.siftec.collect.Collectibles.placeChunk(level, next.x(), next.z());
            }
        });
    }

    /** The nodes whose mound reaches into this chunk. */
    public static Set<Node> nodesTouching(ServerLevel level, int chunkX, int chunkZ) {
        Set<Node> nodes = new HashSet<>(2);
        int minX = (chunkX << 4) - RADIUS, maxX = (chunkX << 4) + 15 + RADIUS;
        int minZ = (chunkZ << 4) - RADIUS, maxZ = (chunkZ << 4) + 15 + RADIUS;
        // mounds never cross a cell edge, so the cells under the chunk's corners are the only candidates
        for (int cx = Math.floorDiv(minX, NodeMap.CELL); cx <= Math.floorDiv(maxX, NodeMap.CELL); cx++) {
            for (int cz = Math.floorDiv(minZ, NodeMap.CELL); cz <= Math.floorDiv(maxZ, NodeMap.CELL); cz++) {
                for (Node n : NodeMap.inCell(level, cx, cz)) {
                    if (n.x() >= minX && n.x() <= maxX && n.z() >= minZ && n.z() <= maxZ) nodes.add(n);
                }
            }
        }
        return nodes;
    }

    /** Places the node blocks that belong in this chunk, once. True if it placed any. */
    public static boolean placeChunk(ServerLevel level, int chunkX, int chunkZ) {
        if (!NodeMap.ready(level) || !level.hasChunk(chunkX, chunkZ)) return false;
        NodeSavedData data = NodeSavedData.get(level.getServer());
        boolean any = false;
        for (Node node : nodesTouching(level, chunkX, chunkZ)) {
            boolean level0 = node.type().where == NodeType.Where.NETHER || node.type().where == NodeType.Where.SULFUR_CAVE;
            int height = Node.SURFACE;
            if (level0) {
                height = data.height(node.key());
                if (height == NodeSavedData.NO_HEIGHT) {
                    // only the chunk with the middle of the node can choose the height
                    if (node.x() >> 4 != chunkX || node.z() >> 4 != chunkZ) continue;
                    height = chooseHeight(level, node);
                    data.setHeight(node.key(), height);
                    // neighbours that loaded earlier have been waiting for this
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if ((dx != 0 || dz != 0) && level.hasChunk(chunkX + dx, chunkZ + dz)) {
                                any |= placePiece(level, data, node, chunkX + dx, chunkZ + dz, height);
                            }
                        }
                    }
                }
            }
            any |= placePiece(level, data, node, chunkX, chunkZ, height);
        }
        return any;
    }

    private static boolean placePiece(ServerLevel level, NodeSavedData data, Node node, int chunkX, int chunkZ, int height) {
        long piece = NodeSavedData.piece(node.key(), chunkX, chunkZ);
        if (data.isPlaced(piece)) return false;
        boolean placed = placeColumns(level, node, chunkX, chunkZ, height);
        data.markPlaced(piece);
        return placed;
    }

    /** The floor a cave or Nether node stands on, found under the middle of the node. */
    private static int chooseHeight(ServerLevel level, Node node) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean nether = node.type().where == NodeType.Where.NETHER;
        if (nether) {
            // the lowest floor above the lava sea, so nodes are on the ground and not up on a ledge
            for (int y = 32; y <= 110; y++) {
                if (isFloor(level, pos, node.x(), y, node.z())) return y;
            }
            return 64;
        }
        for (int y = node.y() + 24; y >= node.y() - 24; y--) {
            if (isFloor(level, pos, node.x(), y, node.z())) return y;
        }
        return node.y();
    }

    private static boolean isFloor(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        BlockState floor = level.getBlockState(pos.set(x, y, z));
        if (floor.isAir() || floor.canBeReplaced() || !floor.getFluidState().isEmpty()) return false;
        return level.getBlockState(pos.set(x, y + 1, z)).isAir() && level.getBlockState(pos.set(x, y + 2, z)).isAir();
    }

    private static boolean placeColumns(ServerLevel level, Node node, int chunkX, int chunkZ, int height) {
        Block ore = ModBlocks.NODES.get(node.type()).get();
        BlockState oreState = ore.defaultBlockState();
        BlockState coreState = node.type() == NodeType.OIL
            ? ModBlocks.OIL_WELL.get().defaultBlockState().setValue(NodeBlock.CORE, true)
            : oreState.setValue(NodeBlock.CORE, true);
        BlockState rock = ModBlocks.NODE_ROCK.get().defaultBlockState();
        boolean pool = node.type() == NodeType.OIL;
        boolean surface = height == Node.SURFACE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                int x = node.x() + dx, z = node.z() + dz;
                if (x >> 4 != chunkX || z >> 4 != chunkZ) continue;
                double r = Math.sqrt(dx * dx + dz * dz);
                long h = hash(node.key(), dx, dz);
                // a ragged outline: the outermost ring is only partly filled
                if (r > RADIUS + 0.3 || r > RADIUS - 0.7 && (h & 3) == 0) continue;

                int y;
                if (surface) {
                    y = ground(level, x, z, pos);
                    if (y == Integer.MIN_VALUE) continue;
                    // in a lake or the sea: build rock up from the bed so the node stands just above the water
                    int filled = 0;
                    while (filled < 48 && !level.getFluidState(pos.set(x, y + 1, z)).isEmpty()) {
                        level.setBlock(pos.set(x, y, z), rock, Block.UPDATE_CLIENTS);
                        y++;
                        filled++;
                    }
                } else {
                    y = height;
                }
                boolean core = dx == 0 && dz == 0;
                boolean centre = r < 1.5;
                boolean rim = r > RADIUS - 1.2;
                int roll = (int) ((h >>> 8) % 100);
                BlockState state;
                if (core) state = coreState;
                else if (pool) state = rim ? rock : oreState;          // an oil pool: crude inside a rock rim
                else state = centre || roll < 45 ? oreState : rock;
                level.setBlock(pos.set(x, y, z), state, Block.UPDATE_CLIENTS);
                any = true;
                // lumps around the flat centre (a pool only has them on its rim)
                int top = y;
                if (!centre && roll >= 70 && (!pool || rim)) {
                    top = y + 1;
                    level.setBlock(pos.set(x, top, z), roll >= 85 && !pool ? oreState : rock, Block.UPDATE_CLIENTS);
                }
                // clear what sits on the mound: plants and snow on the surface, rock in a cave
                for (int up = 1; up <= 2; up++) {
                    BlockState above = level.getBlockState(pos.set(x, top + up, z));
                    if (above.isAir()) continue;
                    if (!surface || above.canBeReplaced() && above.getFluidState().isEmpty()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        return any;
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
