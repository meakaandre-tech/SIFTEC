package com.meakaandre.siftec.power;

import com.meakaandre.siftec.Siftec;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every wire in the world, by the positions of the New Age connectors at its ends: a map that can be read without
 * loading any chunk. New Age only joins connectors that are both loaded; with this map a grid ({@link PowerGrids})
 * reaches over connectors that are not loaded, so a Power Tower line keeps the machines at its far end running while
 * the towers in the middle are not loaded, and the members of unloaded stretches keep their part ({@link PowerMemory}).
 * <p>
 * Kept up to date by the connectors (mixins on New Age's connector): a wire strung or cut, a connector broken (its
 * wires go with it), and a connector that loads (its own list is the truth for one the map does not know yet; one the
 * map knows drops wires the map no longer has, as those were cut while it was unloaded).
 */
public class WireMap extends SavedData {
    private record Entry(String dimension, long pos, List<Long> links) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("dim").forGetter(Entry::dimension),
            Codec.LONG.fieldOf("pos").forGetter(Entry::pos),
            Codec.LONG.listOf().fieldOf("links").forGetter(Entry::links)
        ).apply(i, Entry::new));
    }

    public static final Codec<WireMap> CODEC = Entry.CODEC.listOf().xmap(WireMap::new, WireMap::entries);
    private static final SavedDataType<WireMap> TYPE = new SavedDataType<>(Siftec.id("wires"), WireMap::new, CODEC, null);

    private final Map<String, Long2ObjectOpenHashMap<LongSet>> nodes = new HashMap<>();
    /** Goes up with every change, for caches. */
    private int version;

    public WireMap() {
    }

    private WireMap(List<Entry> entries) {
        for (Entry e : entries) dim(e.dimension()).put(e.pos(), new LongOpenHashSet(e.links()));
    }

    private List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        nodes.forEach((dim, map) -> map.long2ObjectEntrySet().forEach(e -> out.add(new Entry(dim, e.getLongKey(), new ArrayList<>(e.getValue())))));
        return out;
    }

    public static WireMap get(ServerLevel level) {
        return level.getServer().getDataStorage().computeIfAbsent(TYPE);
    }

    private Long2ObjectOpenHashMap<LongSet> dim(String dimension) {
        return nodes.computeIfAbsent(dimension, k -> new Long2ObjectOpenHashMap<>());
    }

    private static LongSet set(Long2ObjectOpenHashMap<LongSet> map, long key) {
        LongSet set = map.get(key);
        if (set == null) {
            set = new LongOpenHashSet();
            map.put(key, set);
        }
        return set;
    }

    private static String key(Level level) {
        return level.dimension().identifier().toString();
    }

    public int version() {
        return version;
    }

    private void changed() {
        version++;
        setDirty();
        PowerGrids.dirty();
    }

    public boolean knows(Level level, BlockPos pos) {
        return dim(key(level)).containsKey(pos.asLong());
    }

    public boolean linked(Level level, BlockPos a, BlockPos b) {
        LongSet set = dim(key(level)).get(a.asLong());
        return set != null && set.contains(b.asLong());
    }

    /** The far ends of the wires at a connector. */
    public Set<BlockPos> links(Level level, BlockPos pos) {
        LongSet set = dim(key(level)).get(pos.asLong());
        Set<BlockPos> out = new LinkedHashSet<>();
        if (set != null) for (long l : set) out.add(BlockPos.of(l));
        return out;
    }

    /** A connector the map did not know yet: its wires as it holds them. */
    public void add(Level level, BlockPos pos, Collection<BlockPos> ends) {
        Long2ObjectOpenHashMap<LongSet> map = dim(key(level));
        LongSet own = set(map, pos.asLong());
        for (BlockPos end : ends) {
            own.add(end.asLong());
            set(map, end.asLong()).add(pos.asLong());
        }
        changed();
    }

    public void link(Level level, BlockPos a, BlockPos b) {
        if (linked(level, a, b)) return;
        Long2ObjectOpenHashMap<LongSet> map = dim(key(level));
        set(map, a.asLong()).add(b.asLong());
        set(map, b.asLong()).add(a.asLong());
        changed();
    }

    public void unlink(Level level, BlockPos a, BlockPos b) {
        Long2ObjectOpenHashMap<LongSet> map = dim(key(level));
        LongSet sa = map.get(a.asLong()), sb = map.get(b.asLong());
        boolean had = sa != null && sa.remove(b.asLong());
        had |= sb != null && sb.remove(a.asLong());
        if (had) changed();
    }

    /** A connector that is gone: its wires go with it. */
    public void remove(Level level, BlockPos pos) {
        Long2ObjectOpenHashMap<LongSet> map = dim(key(level));
        LongSet set = map.remove(pos.asLong());
        if (set == null) return;
        for (long other : set) {
            LongSet so = map.get(other);
            if (so != null) so.remove(pos.asLong());
        }
        changed();
    }

    /** How many connectors and wires the map holds, for the selftests. */
    public String describe(Level level) {
        Long2ObjectOpenHashMap<LongSet> map = dim(key(level));
        int wires = 0;
        for (LongSet s : map.values()) wires += s.size();
        return map.size() + " connectors, " + wires / 2 + " wires";
    }
}
