package com.meakaandre.siftec.building;

import com.meakaandre.siftec.Siftec;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which blocks belong to a building, per dimension: member position to controller position. The protections
 * (hand, wrench, explosions, pistons, drills, contraptions) ask it, so the Create machines inside a building are
 * as safe as its shell. Kept with the world; each controller also writes its members back in when it loads.
 */
public class BuildingIndex extends SavedData {
    public static final Codec<BuildingIndex> CODEC = Codec.unboundedMap(Codec.STRING, Codec.LONG.listOf())
        .xmap(BuildingIndex::new, BuildingIndex::pack);
    private static final SavedDataType<BuildingIndex> TYPE = new SavedDataType<>(Siftec.id("buildings"), BuildingIndex::new, CODEC, null);
    private static final long NONE = Long.MIN_VALUE;

    private final Map<String, Long2LongOpenHashMap> byDimension = new HashMap<>();

    public BuildingIndex() {
    }

    private BuildingIndex(Map<String, List<Long>> packed) {
        packed.forEach((dim, pairs) -> {
            Long2LongOpenHashMap map = map(dim);
            for (int i = 0; i + 1 < pairs.size(); i += 2) map.put(pairs.get(i).longValue(), pairs.get(i + 1).longValue());
        });
    }

    private Map<String, List<Long>> pack() {
        Map<String, List<Long>> out = new HashMap<>();
        byDimension.forEach((dim, map) -> {
            if (map.isEmpty()) return;
            List<Long> pairs = new ArrayList<>(map.size() * 2);
            map.long2LongEntrySet().forEach(e -> {
                pairs.add(e.getLongKey());
                pairs.add(e.getLongValue());
            });
            out.put(dim, pairs);
        });
        return out;
    }

    private Long2LongOpenHashMap map(String dim) {
        return byDimension.computeIfAbsent(dim, k -> {
            Long2LongOpenHashMap m = new Long2LongOpenHashMap();
            m.defaultReturnValue(NONE);
            return m;
        });
    }

    private static String dim(Level level) {
        return level.dimension().identifier().toString();
    }

    // ------------------------------------------------------------------------------------------------ access

    private static @Nullable MinecraftServer server;

    /** Set while a server runs, so the protections (some called with no server at hand) can look things up. */
    public static void serverStarted(MinecraftServer s) {
        server = s;
    }

    public static void serverStopped() {
        server = null;
    }

    public static @Nullable BuildingIndex get(Level level) {
        MinecraftServer s = level.getServer();
        if (s == null) s = server;
        if (level.isClientSide() || s == null) return null;
        return s.getDataStorage().computeIfAbsent(TYPE);
    }

    /** The controller of the building the block at {@code pos} belongs to, or null. */
    public static @Nullable BlockPos controllerOf(Level level, BlockPos pos) {
        BuildingIndex index = get(level);
        if (index == null) return null;
        Long2LongOpenHashMap map = index.byDimension.get(dim(level));
        if (map == null) return null;
        long c = map.get(pos.asLong());
        return c == NONE ? null : BlockPos.of(c);
    }

    public static boolean isMember(Level level, BlockPos pos) {
        return controllerOf(level, pos) != null;
    }

    public void put(Level level, BlockPos member, BlockPos controller) {
        if (map(dim(level)).put(member.asLong(), controller.asLong()) != controller.asLong()) setDirty();
    }

    public void remove(Level level, BlockPos member, BlockPos controller) {
        Long2LongOpenHashMap map = map(dim(level));
        if (map.get(member.asLong()) == controller.asLong()) {
            map.remove(member.asLong());
            setDirty();
        }
    }

    /** How many blocks are listed for one controller (selftests). */
    public int count(Level level, BlockPos controller) {
        Long2LongOpenHashMap map = byDimension.get(dim(level));
        if (map == null) return 0;
        long c = controller.asLong();
        int n = 0;
        for (long v : map.values()) if (v == c) n++;
        return n;
    }
}
