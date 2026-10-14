package com.meakaandre.siftec.collect;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.registry.ModBlocks;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Somersloops in Ancient City portal frames. When the chunk an Ancient City starts in loads, the city is noted; once
 * the chunks round its centre piece are loaded too, the Somersloop is put in the middle of the reinforced deepslate
 * frame, on its sill. Where each city's Somersloop went is saved, so the Object Scanner can point at it.
 * <p>
 * Before a city has been reached, the scanner finds it as the game's own /locate does, from the seed.
 */
public final class AncientCities {
    /** Cities seen in loaded chunks and waiting for the chunks round their frame: start chunk -> its start. */
    private static final Map<Long, StructureStart> WAITING = new HashMap<>();
    /** The nearest city the scanner found from each 256-block square, so /locate's search runs once per square. */
    private static final Map<Long, Located> LOCATED = new HashMap<>();

    /** A remembered locate result, or none found. */
    private record Located(@Nullable BlockPos pos) {
    }

    /** A chunk position as one number (x low, z high). */
    static long pack(int chunkX, int chunkZ) {
        return (chunkX & 0xffffffffL) | ((long) chunkZ << 32);
    }

    private AncientCities() {
    }

    static void clear() {
        WAITING.clear();
        LOCATED.clear();
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 == 7 && !WAITING.isEmpty()) settle(server.overworld());
        });
    }

    // ------------------------------------------------------------------------------------------------ saved

    /** Each city (by its start chunk, packed) and where its Somersloop was put. */
    public static final class Data extends SavedData {
        static final Codec<Data> CODEC = Codec.unboundedMap(Codec.STRING, Codec.LONG).xmap(Data::new, d -> d.placed);
        private static final SavedDataType<Data> TYPE = new SavedDataType<>(Siftec.id("ancient_city_somersloops"), Data::new, CODEC, null);
        final Map<String, Long> placed = new HashMap<>();

        public Data() {
        }

        private Data(Map<String, Long> placed) {
            this.placed.putAll(placed);
        }

        public static Data get(MinecraftServer server) {
            return server.getDataStorage().computeIfAbsent(TYPE);
        }

        /** Where the city starting in this chunk got its Somersloop, or null if it has not yet. */
        public @Nullable BlockPos at(long startChunk) {
            Long packed = placed.get(Long.toString(startChunk));
            return packed == null ? null : BlockPos.of(packed);
        }

        void put(long startChunk, BlockPos pos) {
            placed.put(Long.toString(startChunk), pos.asLong());
            setDirty();
        }

        public Set<String> cities() {
            return placed.keySet();
        }
    }

    // ------------------------------------------------------------------------------------------------ placing

    private static boolean isAncientCity(ServerLevel level, Structure structure) {
        Identifier id = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(structure);
        return id != null && id.equals(BuiltinStructures.ANCIENT_CITY.identifier());
    }

    /** A chunk has loaded: if an Ancient City starts in it and has no Somersloop yet, it waits for its frame. */
    static void noteChunk(ServerLevel level, int chunkX, int chunkZ) {
        if (!level.hasChunk(chunkX, chunkZ)) return;
        ChunkAccess chunk = level.getChunk(chunkX, chunkZ);
        for (Map.Entry<Structure, StructureStart> e : chunk.getAllStarts().entrySet()) {
            StructureStart start = e.getValue();
            if (!start.isValid() || !isAncientCity(level, e.getKey())) continue;
            long key = pack(chunkX, chunkZ);
            if (Data.get(level.getServer()).at(key) == null) WAITING.put(key, start);
        }
    }

    /** The piece the portal frame is in: the city centre; the whole city if no piece says so. */
    private static BoundingBox centre(StructureStart start) {
        for (StructurePiece piece : start.getPieces()) {
            if (piece.toString().contains("city_center")) return piece.getBoundingBox();
        }
        return start.getBoundingBox();
    }

    private static void settle(ServerLevel level) {
        Data data = Data.get(level.getServer());
        for (Iterator<Map.Entry<Long, StructureStart>> it = WAITING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, StructureStart> e = it.next();
            if (data.at(e.getKey()) != null) {
                it.remove();
                continue;
            }
            BoundingBox box = centre(e.getValue());
            boolean loaded = true;
            for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4 && loaded; cx++) {
                for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4 && loaded; cz++) loaded = level.hasChunk(cx, cz);
            }
            if (!loaded) continue;
            it.remove();
            BlockPos at = frame(level, box);
            if (at == null) {
                // no frame found (a city another mod changed): the Somersloop goes in the middle of the centre piece
                at = new BlockPos((box.minX() + box.maxX()) / 2, box.minY() + 1, (box.minZ() + box.maxZ()) / 2);
            }
            level.setBlock(at, ModBlocks.COLLECTIBLES.get(Collectible.SOMERSLOOP).get().defaultBlockState(), Block.UPDATE_CLIENTS);
            data.put(e.getKey(), at);
        }
    }

    /**
     * Where the Somersloop goes: the middle of the reinforced deepslate in the box (the frame is the only reinforced
     * deepslate in a city), dropped onto whatever is under it there.
     */
    private static @Nullable BlockPos frame(ServerLevel level, BoundingBox box) {
        long sx = 0, sy = 0, sz = 0, n = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    if (level.getBlockState(p.set(x, y, z)).is(Blocks.REINFORCED_DEEPSLATE)) {
                        sx += x;
                        sy += y;
                        sz += z;
                        n++;
                    }
                }
            }
        }
        if (n == 0) return null;
        BlockPos middle = new BlockPos((int) (sx / n), (int) (sy / n), (int) (sz / n));
        BlockPos.MutableBlockPos at = middle.mutable();
        // down through the frame's open middle to its sill
        while (at.getY() > box.minY() && level.getBlockState(at.below()).canBeReplaced()) at.move(0, -1, 0);
        if (!level.getBlockState(at).canBeReplaced()) return null;
        return at.immutable();
    }

    // ------------------------------------------------------------------------------------------------ finding

    /**
     * The nearest Ancient City Somersloop from here that the company has not collected: where it was put if its city has
     * been reached, or the city's centre from the seed (as /locate finds it) if not. Server thread only.
     */
    public static @Nullable BlockPos nearest(ServerLevel level, BlockPos from, Set<String> collected) {
        long square = pack(from.getX() >> 8, from.getZ() >> 8);
        Located found = LOCATED.get(square);
        if (found == null) {
            found = new Located(locate(level, from));
            LOCATED.put(square, found);
        }
        BlockPos city = found.pos();
        if (city == null) return null;
        BlockPos placed = Data.get(level.getServer()).at(pack(city.getX() >> 4, city.getZ() >> 4));
        if (placed != null) return collected.contains(CollectibleBlock.key(placed)) ? null : placed;
        return city;
    }

    private static @Nullable BlockPos locate(ServerLevel level, BlockPos from) {
        try {
            Holder<Structure> city = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.ANCIENT_CITY);
            Pair<BlockPos, Holder<Structure>> hit = level.getChunkSource().getGenerator()
                .findNearestMapStructure(level, HolderSet.direct(city), from, 48, false);
            return hit == null ? null : hit.getFirst();
        } catch (RuntimeException e) {
            Siftec.LOGGER.warn("Could not look for Ancient Cities", e);
            return null;
        }
    }
}
