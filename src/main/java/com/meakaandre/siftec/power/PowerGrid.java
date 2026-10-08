package com.meakaandre.siftec.power;

import com.meakaandre.siftec.Siftec;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
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
 * Every Power Line in the world, by the positions of the poles at its ends: a map that can be read without loading
 * any chunk. A long line runs through poles whose chunks are unloaded; the kinetic network jumps over them, from
 * the last loaded pole on one side straight to the first loaded pole on the other ({@link #loadedBeyond}), so the
 * machines at the far end of a line keep their power while the land between is not loaded.
 *
 * Kept up to date by the poles themselves: a link or unlink, a broken pole (its node goes), and a pole that loads
 * (its own list is the truth for a pole the map does not know yet; a pole the map knows drops lines the map no
 * longer has, as those were cut while it was unloaded).
 */
public class PowerGrid extends SavedData {
    private record Entry(String dimension, long pos, List<Long> links) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("dim").forGetter(Entry::dimension),
            Codec.LONG.fieldOf("pos").forGetter(Entry::pos),
            Codec.LONG.listOf().fieldOf("links").forGetter(Entry::links)
        ).apply(i, Entry::new));
    }

    public static final Codec<PowerGrid> CODEC = Entry.CODEC.listOf().xmap(PowerGrid::new, PowerGrid::entries);
    private static final SavedDataType<PowerGrid> TYPE = new SavedDataType<>(Siftec.id("power_lines"), PowerGrid::new, CODEC, null);
    /** Most poles a jump over unloaded land looks through; far more than any real line. */
    private static final int MAX_WALK = 4096;

    private final Map<String, Long2ObjectOpenHashMap<LongSet>> nodes = new HashMap<>();
    /** Goes up with every change, for caches. */
    private int version;

    public PowerGrid() {
    }

    private PowerGrid(List<Entry> entries) {
        for (Entry e : entries) dim(e.dimension()).put(e.pos(), new LongOpenHashSet(e.links()));
    }

    private List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        nodes.forEach((dim, map) -> map.long2ObjectEntrySet().forEach(e -> out.add(new Entry(dim, e.getLongKey(), new ArrayList<>(e.getValue())))));
        return out;
    }

    public static PowerGrid get(ServerLevel level) {
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
    }

    public boolean knows(Level level, BlockPos pos) {
        return dim(key(level)).containsKey(pos.asLong());
    }

    public boolean linked(Level level, BlockPos a, BlockPos b) {
        LongSet set = dim(key(level)).get(a.asLong());
        return set != null && set.contains(b.asLong());
    }

    /** The far ends of the lines at a pole, as positions. */
    public Set<BlockPos> links(Level level, BlockPos pos) {
        LongSet set = dim(key(level)).get(pos.asLong());
        Set<BlockPos> out = new LinkedHashSet<>();
        if (set != null) for (long l : set) out.add(BlockPos.of(l));
        return out;
    }

    /** A pole the map did not know yet: its lines as it holds them. */
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
        Long2ObjectOpenHashMap<LongSet> map = dim(key(level));
        set(map, a.asLong()).add(b.asLong());
        set(map, b.asLong()).add(a.asLong());
        changed();
    }

    public void unlink(Level level, BlockPos a, BlockPos b) {
        Long2ObjectOpenHashMap<LongSet> map = dim(key(level));
        LongSet sa = map.get(a.asLong()), sb = map.get(b.asLong());
        if (sa != null) sa.remove(b.asLong());
        if (sb != null) sb.remove(a.asLong());
        changed();
    }

    /** A pole that is gone: its lines go with it. */
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

    /**
     * The loaded poles reached from {@code from} along the line that starts at the unloaded pole {@code first},
     * walking only through unloaded poles. Reads nothing but this map and which chunks are loaded.
     */
    public List<BlockPos> loadedBeyond(Level level, BlockPos from, BlockPos first) {
        Long2ObjectOpenHashMap<LongSet> map = dim(key(level));
        List<BlockPos> out = new ArrayList<>();
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        seen.add(from.asLong());
        seen.add(first.asLong());
        queue.enqueue(first.asLong());
        while (!queue.isEmpty() && seen.size() < MAX_WALK) {
            LongSet next = map.get(queue.dequeueLong());
            if (next == null) continue;
            for (long l : next) {
                if (!seen.add(l)) continue;
                BlockPos pos = BlockPos.of(l);
                if (level.isLoaded(pos)) out.add(pos);
                else queue.enqueue(l);
            }
        }
        return out;
    }

    /** How many poles and lines the map holds, for the selftests. */
    public String describe(Level level) {
        Long2ObjectOpenHashMap<LongSet> map = dim(key(level));
        int lines = 0;
        for (LongSet s : map.values()) lines += s.size();
        return map.size() + " poles, " + lines / 2 + " lines";
    }
}
