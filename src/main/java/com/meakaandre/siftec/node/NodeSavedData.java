package com.meakaandre.siftec.node;

import com.meakaandre.siftec.Siftec;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the node system has to remember about a world: the point the node map is measured from, which
 * pieces of which nodes are already placed, the height chosen for each node's pad (for an old surface mound,
 * none), which nodes are flat pads, and every placed ore node by position ({@link Site}: its type, look and purity,
 * and when an empty one grows back). The node block itself is a plain vanilla, Create or SIFTEC ore block: what
 * makes a position a node is only this list ({@link NodeSites}).
 */
public class NodeSavedData extends SavedData {
    public static final Codec<NodeSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.BOOL.optionalFieldOf("has_origin", false).forGetter(data -> data.hasOrigin),
        Codec.INT.optionalFieldOf("origin_x", 0).forGetter(data -> data.originX),
        Codec.INT.optionalFieldOf("origin_z", 0).forGetter(data -> data.originZ),
        Codec.LONG.listOf().optionalFieldOf("placed", List.of()).forGetter(data -> new ArrayList<>(data.placed)),
        Codec.LONG.listOf().optionalFieldOf("heights", List.of()).forGetter(NodeSavedData::packHeights),
        Codec.LONG.listOf().optionalFieldOf("flat", List.of()).forGetter(data -> new ArrayList<>(data.flat)),
        SiteEntry.CODEC.listOf().optionalFieldOf("sites", List.of()).forGetter(NodeSavedData::packSites)
    ).apply(instance, NodeSavedData::new));
    private static final SavedDataType<NodeSavedData> TYPE = new SavedDataType<>(
        Siftec.id("nodes"), NodeSavedData::new, CODEC, null
    );
    public static final int NO_HEIGHT = Integer.MIN_VALUE;

    private boolean hasOrigin;
    private int originX, originZ;
    private final LongSet placed = new LongOpenHashSet();
    private final Long2IntOpenHashMap heights = new Long2IntOpenHashMap();
    /** The nodes that had nowhere to stand (height NO_PLACE), readable from the node map's worker thread. */
    private final Set<Long> unplaceable = ConcurrentHashMap.newKeySet();
    /** Nodes placed as a flat pad (all nodes started since pads came in); the others keep their old mound. */
    private final LongSet flat = new LongOpenHashSet();

    public NodeSavedData() {
        heights.defaultReturnValue(NO_HEIGHT);
    }

    private NodeSavedData(boolean hasOrigin, int originX, int originZ, List<Long> placed, List<Long> heights, List<Long> flat, List<SiteEntry> sites) {
        this();
        for (SiteEntry e : sites) {
            NodeLook look = NodeLook.byId(e.look());
            Purity purity = Purity.byName(e.purity());
            if (look == null || look.fluid() || purity == null) continue;
            this.sites.computeIfAbsent(e.dimension(), k -> new Long2ObjectOpenHashMap<>()).put(e.pos(), new Site(look, purity, e.regrowAt()));
            index(e.dimension(), e.pos());
        }
        this.flat.addAll(flat);
        this.hasOrigin = hasOrigin;
        this.originX = originX;
        this.originZ = originZ;
        this.placed.addAll(placed);
        for (int i = 0; i + 1 < heights.size(); i += 2) {
            this.heights.put(heights.get(i).longValue(), heights.get(i + 1).intValue());
            if (heights.get(i + 1).intValue() == NodePlacer.NO_PLACE) unplaceable.add(heights.get(i));
        }
    }

    private List<Long> packHeights() {
        List<Long> out = new ArrayList<>(heights.size() * 2);
        heights.long2IntEntrySet().forEach(e -> {
            out.add(e.getLongKey());
            out.add((long) e.getIntValue());
        });
        return out;
    }

    // ---- ore node sites

    /**
     * One ore node: its look (which gives its type and its block) and purity, and the game time an empty node grows
     * back at ({@link #GROWN} while its ore block stands there).
     */
    public static final class Site {
        public final NodeLook look;
        public final Purity purity;
        long regrowAt;

        Site(NodeLook look, Purity purity, long regrowAt) {
            this.look = look;
            this.purity = purity;
            this.regrowAt = regrowAt;
        }

        public NodeType type() {
            return look.type();
        }

        /** True while the ore stands there; false while the spot is empty and waiting to grow back. */
        public boolean grown() {
            return regrowAt == GROWN;
        }

        /** The game time it grows back at; {@link #GROWN} while grown. */
        public long regrowAt() {
            return regrowAt;
        }

        @Override
        public String toString() {
            return look.id() + " " + purity.getSerializedName() + (grown() ? ", grown" : ", empty until " + regrowAt);
        }
    }

    public static final long GROWN = -1;

    private record SiteEntry(String dimension, long pos, String type, String look, String purity, long regrowAt) {
        static final Codec<SiteEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("dimension").forGetter(SiteEntry::dimension),
            Codec.LONG.fieldOf("pos").forGetter(SiteEntry::pos),
            Codec.STRING.optionalFieldOf("type", "").forGetter(SiteEntry::type),
            Codec.STRING.fieldOf("look").forGetter(SiteEntry::look),
            Codec.STRING.fieldOf("purity").forGetter(SiteEntry::purity),
            Codec.LONG.optionalFieldOf("regrow_at", GROWN).forGetter(SiteEntry::regrowAt)
        ).apply(i, SiteEntry::new));
    }

    /** Every ore node by dimension, then by {@link BlockPos#asLong} position. */
    private final Map<String, Long2ObjectOpenHashMap<Site>> sites = new HashMap<>();
    /** The same nodes' positions by dimension, then by chunk ({@link net.minecraft.world.level.ChunkPos#pack}-style key). */
    private final Map<String, Long2ObjectOpenHashMap<LongArrayList>> byChunk = new HashMap<>();

    /** A chunk's key from a block position's packed long. */
    public static long chunkOf(long pos) {
        BlockPos p = BlockPos.of(pos);
        return chunkKey(p.getX() >> 4, p.getZ() >> 4);
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    private void index(String dimension, long pos) {
        Long2ObjectOpenHashMap<LongArrayList> map = byChunk.computeIfAbsent(dimension, k -> new Long2ObjectOpenHashMap<>());
        long chunk = chunkOf(pos);
        LongArrayList list = map.get(chunk);
        if (list == null) {
            list = new LongArrayList(2);
            map.put(chunk, list);
        }
        if (!list.contains(pos)) list.add(pos);
    }

    private void unindex(String dimension, long pos) {
        var map = byChunk.get(dimension);
        if (map == null) return;
        long chunk = chunkOf(pos);
        var list = map.get(chunk);
        if (list == null) return;
        list.rem(pos);
        if (list.isEmpty()) map.remove(chunk);
    }

    /** The ore nodes in one chunk of a dimension, by position; null when it has none. Read only. */
    public it.unimi.dsi.fastutil.longs.@Nullable LongList sitesIn(String dimension, long chunk) {
        var map = byChunk.get(dimension);
        return map == null ? null : map.get(chunk);
    }

    /** How many chunks of a dimension hold ore nodes. */
    public int chunksWithSites(String dimension) {
        var map = byChunk.get(dimension);
        return map == null ? 0 : map.size();
    }

    /** The chunks of a dimension that hold ore nodes (keys as {@link #chunkKey}). Read only. */
    public LongSet siteChunks(String dimension) {
        var map = byChunk.get(dimension);
        return map == null ? LongSets.EMPTY_SET : map.keySet();
    }

    private List<SiteEntry> packSites() {
        List<SiteEntry> out = new ArrayList<>();
        sites.forEach((dim, map) -> map.long2ObjectEntrySet().forEach(e -> {
            Site s = e.getValue();
            out.add(new SiteEntry(dim, e.getLongKey(), s.type().id(), s.look.id(), s.purity.getSerializedName(), s.regrowAt));
        }));
        return out;
    }

    /** The ore nodes of one dimension, by position; never null. */
    public Long2ObjectOpenHashMap<Site> sites(String dimension) {
        return sites.computeIfAbsent(dimension, k -> new Long2ObjectOpenHashMap<>());
    }

    public @Nullable Site site(String dimension, long pos) {
        Long2ObjectOpenHashMap<Site> map = sites.get(dimension);
        return map == null ? null : map.get(pos);
    }

    /** Makes a position an ore node (grown), or changes the one there. */
    public Site putSite(String dimension, long pos, NodeLook look, Purity purity, long regrowAt) {
        Site site = new Site(look, purity, regrowAt);
        sites(dimension).put(pos, site);
        index(dimension, pos);
        setDirty();
        return site;
    }

    public void removeSite(String dimension, long pos) {
        Long2ObjectOpenHashMap<Site> map = sites.get(dimension);
        if (map != null && map.remove(pos) != null) {
            unindex(dimension, pos);
            setDirty();
        }
    }

    public void setRegrowAt(Site site, long regrowAt) {
        if (site.regrowAt == regrowAt) return;
        site.regrowAt = regrowAt;
        setDirty();
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
        if (y == NodePlacer.NO_PLACE) unplaceable.add(node);
        else unplaceable.remove(node);
        setDirty();
    }

    /** The nodes left out for having nowhere to stand: a live view, safe to read from any thread. */
    public Set<Long> unplaceable() {
        return Collections.unmodifiableSet(unplaceable);
    }
}
