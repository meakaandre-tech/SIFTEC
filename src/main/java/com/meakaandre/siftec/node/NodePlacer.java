package com.meakaandre.siftec.node;

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
 * Puts node sites into the world, like resource nodes in Satisfactory. A site is a level, round <b>patch</b> of
 * unbreakable stone 7 blocks across (the node's own natural stone, see {@link NodeLook}); under it, unbreakable fill
 * runs down to solid ground, flaring out so the patch sits in the terrain instead of on a pillar; above it the ground
 * is cut back so the patch is open. The <b>node</b>, the real ore block, regrowing when mined and kept as a node by
 * its position ({@link NodeSites}), stands on its middle, and
 * a <b>clump</b> of ordinary breakable blocks (the stone, the ore and the raw ore block, more ore the purer the node)
 * is piled over and round it; it is placed once and never comes back, and a miner can only go on once it is mined.
 * Each chunk places only its own columns of a site, the first time it loads, so no neighbouring chunk is ever forced
 * to load or generate. The height of a surface patch comes from the world generator (the middle height of the ground
 * across the patch), so every chunk works out the same one. Cave and Nether patches take the floor found when the
 * chunk holding the middle of the node loads.
 */
public final class NodePlacer {
    /** How near a block has to be to a node to tell which node it is. */
    public static final int RADIUS = 3;
    /** The patch: every column within this distance of the node. 3.5 gives a round pad 7 blocks across (37 blocks). */
    public static final double PAD_RADIUS = 3.5;
    /** How far the fill under the pad flares out (one block out per block down), and how far the cut above it reaches. */
    private static final int FLARE = 3;
    /** How far from the node a site can change blocks. */
    public static final int REACH = 7;
    /** Air kept above the patch: the node, its clump and a miner over it. */
    private static final int HEADROOM = 6;
    /** Trees and plants are cleared this far above the pad. */
    private static final int CLEAR_PLANTS = 24;
    /** The deepest the fill under a pad goes looking for solid ground. */
    private static final int MAX_FILL = 40;

    private record Queued(ResourceKey<Level> dimension, int x, int z) {
    }

    private static final ArrayDeque<Queued> QUEUE = new ArrayDeque<>();
    /** Chunks whose cells are still being worked out on the worker thread; tried again a little later. */
    private static final ArrayDeque<Queued> WAITING = new ArrayDeque<>();

    private NodePlacer() {
    }

    public static void clear() {
        synchronized (QUEUE) {
            QUEUE.clear();
        }
        WAITING.clear();
    }

    public static void register() {
        // a new world (or the next single player world) starts with nothing remembered from the last one
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            clear();
            NodeMap.clear();
            com.meakaandre.siftec.collect.Collectibles.clear();
        });
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
            if (server.getTickCount() % 10 == 0 && !WAITING.isEmpty()) {
                synchronized (QUEUE) {
                    QUEUE.addAll(WAITING);
                }
                WAITING.clear();
            }
            int placed = 0;
            for (int i = 0; i < 512 && placed < 8; i++) {
                Queued next;
                synchronized (QUEUE) {
                    next = QUEUE.poll();
                }
                if (next == null) return;
                ServerLevel level = server.getLevel(next.dimension());
                if (level == null || !NodeMap.ready(level) || !level.hasChunk(next.x(), next.z())) continue;
                // working a cell out samples the world generator: done on the worker thread, the chunk waits for it
                if (!cellsReady(level, next.x(), next.z())) {
                    WAITING.add(next);
                    continue;
                }
                if (placeChunk(level, next.x(), next.z())) placed++;
                com.meakaandre.siftec.collect.Collectibles.placeChunk(level, next.x(), next.z());
            }
        });
    }

    /** True if every cell a chunk's nodes and collectibles can come from is worked out; asks for the missing ones. */
    private static boolean cellsReady(ServerLevel level, int chunkX, int chunkZ) {
        boolean ready = true;
        int minX = (chunkX << 4) - REACH, maxX = (chunkX << 4) + 15 + REACH;
        int minZ = (chunkZ << 4) - REACH, maxZ = (chunkZ << 4) + 15 + REACH;
        for (int cx = Math.floorDiv(minX, NodeMap.CELL); cx <= Math.floorDiv(maxX, NodeMap.CELL); cx++) {
            for (int cz = Math.floorDiv(minZ, NodeMap.CELL); cz <= Math.floorDiv(maxZ, NodeMap.CELL); cz++) {
                if (!NodeMap.cached(level, cx, cz)) {
                    NodeMap.prefetch(level, cx, cz);
                    ready = false;
                }
            }
        }
        if (!com.meakaandre.siftec.collect.Collectibles.cached(level, chunkX, chunkZ)) {
            com.meakaandre.siftec.collect.Collectibles.prefetch(level, chunkX, chunkZ);
            ready = false;
        }
        return ready;
    }

    /** The nodes whose site reaches into this chunk. */
    public static Set<Node> nodesTouching(ServerLevel level, int chunkX, int chunkZ) {
        return nodesTouching(level, chunkX, chunkZ, REACH);
    }

    private static Set<Node> nodesTouching(ServerLevel level, int chunkX, int chunkZ, int reach) {
        Set<Node> nodes = new HashSet<>(2);
        int minX = (chunkX << 4) - reach, maxX = (chunkX << 4) + 15 + reach;
        int minZ = (chunkZ << 4) - reach, maxZ = (chunkZ << 4) + 15 + reach;
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
                if (height == NO_PLACE) continue;
                if (height == NodeSavedData.NO_HEIGHT) {
                    // only the chunk with the middle of the node can choose the height
                    if (node.x() >> 4 != chunkX || node.z() >> 4 != chunkZ) continue;
                    height = chooseHeight(level, node);
                    data.setHeight(node.key(), height);
                    if (height == NO_PLACE) continue;
                    // neighbours that loaded earlier have been waiting for this
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if ((dx != 0 || dz != 0) && level.hasChunk(chunkX + dx, chunkZ + dz)) {
                                any |= placePiece(level, data, node, chunkX + dx, chunkZ + dz, height);
                            }
                        }
                    }
                }
            } else {
                height = data.height(node.key());
                if (height == NodeSavedData.NO_HEIGHT) {
                    height = NodeMap.padHeight(level, node);
                    data.setHeight(node.key(), height);
                }
            }
            any |= placePiece(level, data, node, chunkX, chunkZ, height);
            // the chunk holding the node keeps it as a node by its position; a site placed before nodes were kept that
            // way is taken in here too, the first time its chunk loads, and its ore grows back whatever is there now
            if (!node.type().isFluid() && node.x() >> 4 == chunkX && node.z() >> 4 == chunkZ) {
                NodeSites.ensure(level, new BlockPos(node.x(), height + 1, node.z()), NodeLook.of(node), node.purity());
            }
        }
        return any;
    }

    private static boolean placePiece(ServerLevel level, NodeSavedData data, Node node, int chunkX, int chunkZ, int height) {
        long piece = NodeSavedData.piece(node.key(), chunkX, chunkZ);
        if (data.isPlaced(piece)) return false;
        boolean placed = placeSite(level, node, chunkX, chunkZ, height);
        data.markPlaced(piece);
        return placed;
    }

    /** The floor a cave or Nether node stands on, found under the middle of the node. NO_PLACE if there is none fit to stand on. */
    private static int chooseHeight(ServerLevel level, Node node) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean nether = node.type().where == NodeType.Where.NETHER;
        if (nether) {
            // every floor in the column that the whole mound can stand on (no lava or drop under its middle)
            java.util.List<Integer> floors = new java.util.ArrayList<>();
            int top = Math.min(level.getMaxY() - 3, level.getMinY() + level.dimensionType().logicalHeight() - 3);
            for (int y = level.getMinY() + 1; y <= top; y++) {
                if (isFloor(level, pos, node.x(), y, node.z()) && supported(level, pos, node, y)) floors.add(y);
            }
            if (floors.isEmpty()) return NO_PLACE;
            if (level.getHeight() <= 256) {
                // an ordinary Nether: the lowest floor above the lava sea, so nodes are on the ground and not up on a ledge
                for (int y : floors) if (y > level.getSeaLevel()) return y;
                return floors.getFirst();
            }
            // a tall Nether of many layers: any of its floors, picked by the node, so every layer gets some
            return floors.get((int) Long.remainderUnsigned(hash(node.key(), 7, 11), floors.size()));
        }
        for (int d = 0; d <= 32; d++) {
            if (isFloor(level, pos, node.x(), node.y() - d, node.z())) return node.y() - d;
            if (d > 0 && isFloor(level, pos, node.x(), node.y() + d, node.z())) return node.y() + d;
        }
        return node.y();
    }

    /** True if most of a pad at this height has solid ground under it: not over lava or a drop. */
    private static boolean supported(ServerLevel level, BlockPos.MutableBlockPos pos, Node node, int y) {
        int solid = 0, total = 0;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (dx * dx + dz * dz > PAD_RADIUS * PAD_RADIUS) continue;
                total++;
                BlockState state = level.getBlockState(pos.set(node.x() + dx, y, node.z() + dz));
                BlockState under = level.getBlockState(pos.set(node.x() + dx, y - 1, node.z() + dz));
                if (!state.isAir() && state.getFluidState().isEmpty() || !under.isAir() && under.getFluidState().isEmpty()) solid++;
            }
        }
        return solid * 4 >= total * 3;
    }

    /** Height recorded for a cave or Nether node that has nowhere to stand: it is left out. */
    public static final int NO_PLACE = Integer.MIN_VALUE + 1;

    private static boolean isFloor(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        BlockState floor = level.getBlockState(pos.set(x, y, z));
        if (floor.isAir() || floor.canBeReplaced() || !floor.getFluidState().isEmpty()) return false;
        return level.getBlockState(pos.set(x, y + 1, z)).isAir() && level.getBlockState(pos.set(x, y + 2, z)).isAir();
    }

    /** The node block of a node: its plain ore block, or a fluid node's well block, which carries the purity. */
    public static BlockState coreState(Node node) {
        NodeLook look = NodeLook.of(node);
        if (!look.fluid()) return look.nodeState();
        return ModBlocks.NODES.get(look.id()).get().defaultBlockState().setValue(com.meakaandre.siftec.block.NodeBlock.PURITY, node.purity());
    }

    /** True for blocks a site must never remove: other unbreakable blocks (bedrock, nodes, collectibles) and anything with a block entity. */
    private static boolean keep(ServerLevel level, BlockState state, BlockPos pos) {
        return state.hasBlockEntity() || state.getDestroySpeed(level, pos) < 0;
    }

    /** Plants, trees and snow: what is cleared off a pad for a long way up. */
    private static boolean plant(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(Blocks.SNOW) || state.is(Blocks.BAMBOO)
            || state.is(Blocks.CACTUS) || state.is(Blocks.SUGAR_CANE) || state.is(Blocks.VINE) || state.canBeReplaced() && state.getFluidState().isEmpty();
    }

    /**
     * One chunk's columns of a site whose patch is at {@code height}: the patch and, under it, fill down to solid
     * ground (flaring out one block per block down); above it, the ground cut back (narrow at the patch, wider
     * higher up) and plants cleared; the node on the middle of the patch and the clump over it.
     */
    private static boolean placeSite(ServerLevel level, Node node, int chunkX, int chunkZ, int height) {
        NodeLook look = NodeLook.of(node);
        // the patch and the fill under it are one block: the node's own natural stone, unbreakable
        BlockState pad = ModBlocks.PATCHES.get(look.patchName()).get().defaultBlockState();
        BlockState fill = pad;
        BlockState air = Blocks.AIR.defaultBlockState();
        boolean cave = node.type().where == NodeType.Where.SULFUR_CAVE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int flags = Block.UPDATE_CLIENTS;
        boolean any = false;
        int minY = level.getMinY();
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dz = -REACH; dz <= REACH; dz++) {
                int x = node.x() + dx, z = node.z() + dz;
                if (x >> 4 != chunkX || z >> 4 != chunkZ) continue;
                double r = Math.sqrt(dx * dx + dz * dz);
                // how far outside the pad this column is, in whole blocks (0 on the pad)
                int out = r <= PAD_RADIUS ? 0 : (int) Math.ceil(r - PAD_RADIUS);
                if (out > FLARE) continue;
                // a cave pad never cuts up to the surface: the cut stops a few blocks under the ground above
                int roof = cave ? level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 4 : Integer.MAX_VALUE;
                // the pad and the fill below it; the flare starts lower the further out it is
                int top = height - out;
                for (int y = top, depth = 0; y > minY && depth < MAX_FILL; y--, depth++) {
                    BlockState here = level.getBlockState(pos.set(x, y, z));
                    boolean solid = !here.isAir() && !here.canBeReplaced() && here.getFluidState().isEmpty() && !plant(here);
                    if (out == 0 && y == height) {
                        if (keep(level, here, pos)) break;
                        level.setBlock(pos, pad, flags);
                        any = true;
                        continue;
                    }
                    if (solid && (out > 0 || y < height)) {
                        // the fill joins the ground: one more block of fill where the ground is soft, then stop
                        break;
                    }
                    if (keep(level, here, pos)) break;
                    level.setBlock(pos, fill, flags);
                    any = true;
                }
                // the cut: open air over the pad, stepping back one block per block up around it
                for (int up = 1 + out; up <= HEADROOM && height + up < roof; up++) {
                    BlockState here = level.getBlockState(pos.set(x, height + up, z));
                    if (here.isAir() || keep(level, here, pos)) continue;
                    if (!here.getFluidState().isEmpty() && out > 0) continue;
                    level.setBlock(pos, air, flags);
                }
                // trees, plants and snow over the site
                if (!cave) {
                    for (int up = 1; up <= CLEAR_PLANTS; up++) {
                        BlockState here = level.getBlockState(pos.set(x, height + up, z));
                        if (!here.isAir() && plant(here) && !keep(level, here, pos)) level.setBlock(pos, air, flags);
                    }
                }
                if (dx == 0 && dz == 0) {
                    level.setBlock(pos.set(x, height + 1, z), coreState(node), flags);
                    any = true;
                }
                // the clump: placed once with the site, never again
                for (int dy = 0; dy < CLUMP_HEIGHT; dy++) {
                    if (!isClump(node, dx, dy, dz)) continue;
                    BlockState here = level.getBlockState(pos.set(x, height + 1 + dy, z));
                    if (!here.isAir() && !here.canBeReplaced() || keep(level, here, pos)) continue;
                    level.setBlock(pos, clumpBlock(node, look, dx, dy, dz), flags);
                    any = true;
                }
            }
        }
        return any;
    }

    // ---- the clump: a small pile over and round the node. Offsets are from the node block; dy 0 is the node's level.

    /** Every cell a clump can fill: two rings round the node at its level, a cross (and some corners) above, a cap on top. */
    private static final int[][] CLUMP_SPACE;
    private static final int CLUMP_HEIGHT = 3;

    static {
        java.util.List<int[]> cells = new java.util.ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            int r2 = dx * dx + dz * dz;
            if (r2 > 0 && r2 <= 4) cells.add(new int[]{dx, 0, dz});
            if (r2 <= 2) cells.add(new int[]{dx, 1, dz});
            if (r2 == 0) cells.add(new int[]{dx, 2, dz});
        }
        CLUMP_SPACE = cells.toArray(new int[0][]);
    }

    /** True if this node's clump fills this cell: the inner ring, the cross and the cap always; the rest by the node. */
    public static boolean isClump(Node node, int dx, int dy, int dz) {
        int r2 = dx * dx + dz * dz;
        if (dy < 0 || dy >= CLUMP_HEIGHT) return false;
        if (dy == 0) return r2 > 0 && (r2 <= 2 || r2 == 4 && (hash(node.key(), dx, dz + 37) & 1) == 0);
        if (dy == 1) return r2 <= 1 || r2 == 2 && (hash(node.key(), dx, dz + 71) & 1) == 0;
        return r2 == 0;
    }

    /**
     * Mostly the patch's stone on an impure node, a mix of stone, ore and raw ore blocks on a normal one, mostly ore and
     * raw ore blocks on a pure one. Where there is no ordinary ore (bauxite, SAM, uranium...) its share is the clump
     * ore block's; a stone node's clump is all its stone.
     */
    private static BlockState clumpBlock(Node node, NodeLook look, int dx, int dy, int dz) {
        int roll = (int) ((hash(node.key(), dx * 7 + dy, dz) >>> 8) % 100);
        int stone = switch (node.purity()) {
            case IMPURE -> 75;
            case NORMAL -> 40;
            case PURE -> 10;
        };
        int ore = switch (node.purity()) {
            case IMPURE -> 20;
            case NORMAL -> 35;
            case PURE -> 45;
        };
        if (roll < stone) return look.patchStone();
        return roll < stone + ore ? look.oreState() : look.clumpState();
    }

    /** How many blocks still stand where a clump can be round the node block at {@code core}: 0 once it is mined away. */
    public static int clumpLeft(net.minecraft.world.level.Level level, BlockPos core) {
        int left = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int[] c : CLUMP_SPACE) {
            BlockState here = level.getBlockState(pos.set(core.getX() + c[0], core.getY() + c[1], core.getZ() + c[2]));
            if (!here.isAir() && !here.canBeReplaced() && !(here.getBlock() instanceof com.meakaandre.siftec.block.NodePadBlock)) left++;
        }
        return left;
    }

    /** Puts a node's whole clump round its node block at {@code core}, over whatever is there (the selftests' showroom). */
    public static void placeClump(ServerLevel level, Node node, BlockPos core) {
        NodeLook look = NodeLook.of(node);
        for (int[] c : CLUMP_SPACE) {
            if (isClump(node, c[0], c[1], c[2])) level.setBlock(core.offset(c[0], c[1], c[2]), clumpBlock(node, look, c[0], c[1], c[2]), Block.UPDATE_CLIENTS);
        }
    }

    /** The cells this node's clump fills, round its node block at {@code core}. */
    public static java.util.List<BlockPos> clumpCells(Node node, BlockPos core) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (int[] c : CLUMP_SPACE) if (isClump(node, c[0], c[1], c[2])) out.add(core.offset(c[0], c[1], c[2]));
        return out;
    }

    private static long hash(long key, int dx, int dz) {
        long v = key * 0x9E3779B97F4A7C15L + dx * 31L + dz * 131L;
        v ^= v >>> 29;
        v *= 0xBF58476D1CE4E5B9L;
        v ^= v >>> 32;
        return v & Long.MAX_VALUE;
    }
}
