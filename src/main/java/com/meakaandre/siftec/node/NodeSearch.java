package com.meakaandre.siftec.node;

import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Finds the nearest node of a type, ring of cells by ring, within the world border and a time limit. */
final class NodeSearch {
    /** A sulfur search looks at most this many cells out. */
    private static final int SULFUR_MAX_CELLS = 24;

    private NodeSearch() {
    }

    /** The nearest node of a type in this dimension, searched ring by ring. Runs where it is called. */
    static Optional<Node> nearest(ServerLevel level, double x, double z, NodeType type, int maxCells) {
        return nearest(level, x, z, type, maxCells, Terrain.Border.NONE, Long.MAX_VALUE);
    }

    static Optional<Node> nearest(ServerLevel level, double x, double z, NodeType type, int maxCells, Terrain.Border border, long nanos) {
        if (!NodeMap.hasNodes(level) || NodeMap.origin == null || type.dimension() != level.dimension()) return Optional.empty();
        long deadline = nanos == Long.MAX_VALUE ? Long.MAX_VALUE : System.nanoTime() + nanos;
        int cx = Math.floorDiv((int) Math.floor(x), NodeMap.CELL);
        int cz = Math.floorDiv((int) Math.floor(z), NodeMap.CELL);
        boolean cave = type == NodeType.SULFUR;
        // finding a sulfur cave means testing the biome many times per cell, so that search stays closer
        if (cave) maxCells = Math.min(maxCells, SULFUR_MAX_CELLS);
        Node best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int ring = 0; ring <= maxCells; ring++) {
            // a node in ring r is at least (r - 1) cells away, so once the best is closer than that we are done
            if (best != null && bestDistance <= (ring - 1) * (double) NodeMap.CELL) break;
            if (System.nanoTime() > deadline) break;
            // past the world border there is nothing to offer
            if (ringOutside(border, cx, cz, ring)) {
                if (ring > 0 && ringOutside(border, cx, cz, ring - 1)) break;
                continue;
            }
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    if (cellOutside(border, cx + dx, cz + dz)) continue;
                    List<Node> nodes = cave ? NodeMap.sulfurIn(level, cx + dx, cz + dz).map(List::of).orElse(List.of())
                        : NodeMap.surface(level, cx + dx, cz + dz);
                    for (Node node : nodes) {
                        if (node.type() != type || NodeMap.placeless(node) || !border.contains(node.x(), node.z())) continue;
                        double distance = node.distanceTo(x, z);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = node;
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean cellOutside(Terrain.Border border, int cx, int cz) {
        return ringOutside(border, cx, cz, 0);
    }

    /** True if the whole square ring of cells around (cx, cz) lies outside the border. */
    private static boolean ringOutside(Terrain.Border border, int cx, int cz, int ring) {
        double minX = (cx - ring) * (double) NodeMap.CELL, maxX = (cx + ring + 1) * (double) NodeMap.CELL;
        double minZ = (cz - ring) * (double) NodeMap.CELL, maxZ = (cz + ring + 1) * (double) NodeMap.CELL;
        boolean overlaps = maxX > border.minX() && minX < border.maxX() && maxZ > border.minZ() && minZ < border.maxZ();
        if (!overlaps) return true;
        if (ring == 0) return false;
        // the ring is the square minus its inside: outside if the inside covers all of the border
        double inMinX = (cx - ring + 1) * (double) NodeMap.CELL, inMaxX = (cx + ring) * (double) NodeMap.CELL;
        double inMinZ = (cz - ring + 1) * (double) NodeMap.CELL, inMaxZ = (cz + ring) * (double) NodeMap.CELL;
        return inMinX <= border.minX() && inMaxX >= border.maxX() && inMinZ <= border.minZ() && inMaxZ >= border.maxZ();
    }

    /**
     * Runs a search on the worker thread and hands the answer to {@code then} on the server thread. The
     * world border is read now, on the calling (server) thread.
     */
    static void nearestAsync(ServerLevel level, double x, double z, NodeType type, int maxCells, Consumer<Optional<Node>> then) {
        Terrain.Border border = Terrain.Border.of(level);
        NodeWorker.async(level.getServer(), () -> nearest(level, x, z, type, maxCells, border, NodeMap.SEARCH_NANOS),
            found -> then.accept(found == null ? Optional.empty() : found));
    }
}
