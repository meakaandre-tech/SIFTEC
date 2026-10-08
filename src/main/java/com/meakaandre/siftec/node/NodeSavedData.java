package com.meakaandre.siftec.node;

import com.meakaandre.siftec.Siftec;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * What the node system has to remember about a world: the point the node map is measured from, which
 * pieces of which nodes are already placed, the height chosen for each node's pad (for an old surface mound,
 * none), and which nodes are flat pads.
 */
public class NodeSavedData extends SavedData {
    public static final Codec<NodeSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.BOOL.optionalFieldOf("has_origin", false).forGetter(data -> data.hasOrigin),
        Codec.INT.optionalFieldOf("origin_x", 0).forGetter(data -> data.originX),
        Codec.INT.optionalFieldOf("origin_z", 0).forGetter(data -> data.originZ),
        Codec.LONG.listOf().optionalFieldOf("placed", List.of()).forGetter(data -> new ArrayList<>(data.placed)),
        Codec.LONG.listOf().optionalFieldOf("heights", List.of()).forGetter(NodeSavedData::packHeights),
        Codec.LONG.listOf().optionalFieldOf("flat", List.of()).forGetter(data -> new ArrayList<>(data.flat))
    ).apply(instance, NodeSavedData::new));
    private static final SavedDataType<NodeSavedData> TYPE = new SavedDataType<>(
        Siftec.id("nodes"), NodeSavedData::new, CODEC, null
    );
    public static final int NO_HEIGHT = Integer.MIN_VALUE;

    private boolean hasOrigin;
    private int originX, originZ;
    private final LongSet placed = new LongOpenHashSet();
    private final Long2IntOpenHashMap heights = new Long2IntOpenHashMap();
    /** Nodes placed as a flat pad (all nodes started since pads came in); the others keep their old mound. */
    private final LongSet flat = new LongOpenHashSet();

    public NodeSavedData() {
        heights.defaultReturnValue(NO_HEIGHT);
    }

    private NodeSavedData(boolean hasOrigin, int originX, int originZ, List<Long> placed, List<Long> heights, List<Long> flat) {
        this();
        this.flat.addAll(flat);
        this.hasOrigin = hasOrigin;
        this.originX = originX;
        this.originZ = originZ;
        this.placed.addAll(placed);
        for (int i = 0; i + 1 < heights.size(); i += 2) this.heights.put(heights.get(i).longValue(), heights.get(i + 1).intValue());
    }

    private List<Long> packHeights() {
        List<Long> out = new ArrayList<>(heights.size() * 2);
        heights.long2IntEntrySet().forEach(e -> {
            out.add(e.getLongKey());
            out.add((long) e.getIntValue());
        });
        return out;
    }

    public static NodeSavedData get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    /**
     * The world spawn as it was when the world was first played. The starter nodes and the purity falloff
     * are measured from here, and it never moves again, even if the spawn point is changed later.
     */
    public boolean hasOrigin() {
        return hasOrigin;
    }

    public int originX() {
        return originX;
    }

    public int originZ() {
        return originZ;
    }

    public void setOrigin(int x, int z) {
        hasOrigin = true;
        originX = x;
        originZ = z;
        setDirty();
    }

    /** A key for "this node's blocks in this chunk". */
    public static long piece(long node, int chunkX, int chunkZ) {
        long v = node * 0x9E3779B97F4A7C15L + chunkX * 0xC2B2AE3D27D4EB4FL + chunkZ * 0x165667B19E3779F9L;
        v ^= v >>> 31;
        return v * 0xBF58476D1CE4E5B9L;
    }

    public boolean isPlaced(long piece) {
        return placed.contains(piece);
    }

    public void markPlaced(long piece) {
        if (placed.add(piece)) setDirty();
    }

    public int height(long node) {
        return heights.get(node);
    }

    public void setHeight(long node, int y) {
        heights.put(node, y);
        setDirty();
    }

    public boolean isFlat(long node) {
        return flat.contains(node);
    }

    public void markFlat(long node) {
        if (flat.add(node)) setDirty();
    }
}
