package com.meakaandre.siftec.collect;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.registry.ModBlocks;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Somersloops in Ancient City portal frames. When the chunk an Ancient City starts in loads, the city is noted; once
 * the chunks round its centre piece are loaded too, the Somersloop is put in the middle of the reinforced deepslate
 * frame, on its sill. Where each city's Somersloop went is saved, so the Object Scanner can point at it.
 * <p>
 * Before a city has been reached, the scanner finds it from the seed: where the game's structure placement would put
 * one, in a deep dark biome. That search only reads the seed and the biome noise, on the node map's worker thread, so it
 * never loads or generates a chunk and never holds up the server.
 */
public final class AncientCities {
    /** Cities seen in loaded chunks and waiting for the chunks round their frame: start chunk -> its start. */
    private static final Map<Long, StructureStart> WAITING = new HashMap<>();
    /**
     * The nearest cities found from each 256-block square, nearest first, so the search runs once per square. A
     * city whose Somersloop the company has collected is passed over for the next one in the list.
     */
    private static final Map<Long, List<BlockPos>> LOCATED = new java.util.concurrent.ConcurrentHashMap<>();
    /** Squares remembered at most; the cache is emptied when it grows past this. */
    private static final int LOCATED_MAX = 512;
    /** How many cities each square remembers, and how far out (in placement regions) the search looks. */
    private static final int CITIES_KEPT = 6, SEARCH_REGIONS = 24;

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
     * The cities nearest a spot, nearest first (their start chunks' middles). Reads only the seed and the biome noise:
     * safe on the node map's worker thread, and it never loads or generates a chunk. Cached by 256-block square.
     */
    public static List<BlockPos> candidates(ServerLevel level, BlockPos from) {
        long square = pack(from.getX() >> 8, from.getZ() >> 8);
        List<BlockPos> known = LOCATED.get(square);
        if (known != null) return known;
        List<BlockPos> found = locate(level, from);
        if (LOCATED.size() >= LOCATED_MAX) LOCATED.clear();
        LOCATED.put(square, found);
        return found;
    }

    /**
     * Of these cities (from {@link #candidates}), the nearest Somersloop the company has not collected: where it was put
     * if its city has been reached, or the city's centre if not. Once one is collected the next city is offered. Server
     * thread only.
     */
    public static @Nullable BlockPos pick(MinecraftServer server, List<BlockPos> cities, Set<String> collected) {
        Data data = Data.get(server);
        for (BlockPos city : cities) {
            BlockPos placed = data.at(pack(city.getX() >> 4, city.getZ() >> 4));
            if (placed == null) return city;
            if (!collected.contains(CollectibleBlock.key(placed))) return placed;
        }
        return null;
    }

    private static List<BlockPos> locate(ServerLevel level, BlockPos from) {
        try {
            Holder<Structure> city = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.ANCIENT_CITY);
            var state = level.getChunkSource().getGeneratorState();
            List<BlockPos> out = new java.util.ArrayList<>();
            for (var placement : state.getPlacementsForStructure(city)) {
                if (placement instanceof net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement spread) {
                    out.addAll(spreadCities(level, state, spread, city.value(), from));
                }
            }
            out.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(new BlockPos(from.getX(), p.getY(), from.getZ()))));
            return List.copyOf(out.subList(0, Math.min(CITIES_KEPT, out.size())));
        } catch (RuntimeException e) {
            Siftec.LOGGER.warn("Could not look for Ancient Cities", e);
            return List.of();
        }
    }

    /**
     * The chunks a random-spread placement offers round a spot, ring by ring, that pass its own frequency and exclusion
     * tests and lie in a biome the city may start in (sampled at the city's depth). Stops a ring after it has enough.
     */
    private static List<BlockPos> spreadCities(ServerLevel level, net.minecraft.world.level.chunk.ChunkGeneratorStructureState state,
                                               net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement spread,
                                               Structure city, BlockPos from) {
        List<BlockPos> out = new java.util.ArrayList<>();
        int spacing = spread.spacing();
        int rx = Math.floorDiv(from.getX() >> 4, spacing), rz = Math.floorDiv(from.getZ() >> 4, spacing);
        long seed = state.getLevelSeed();
        HolderSet<net.minecraft.world.level.biome.Biome> biomes = city.biomes();
        int y = Math.max(level.getMinY() + 8, -27);
        int extra = -1;
        for (int ring = 0; ring <= SEARCH_REGIONS; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    var chunk = spread.getPotentialStructureChunk(seed, rx + dx, rz + dz);
                    if (!spread.isStructureChunk(state, chunk.x(), chunk.z())) continue;
                    int x = (chunk.x() << 4) + 8, z = (chunk.z() << 4) + 8;
                    if (!biomes.contains(com.meakaandre.siftec.node.Terrain.biome(level, x, y, z))) continue;
                    out.add(new BlockPos(x, y, z));
                }
            }
            // one ring past the one where enough turned up: a city in the next ring can still be nearer than the last found
            if (extra < 0 && out.size() >= CITIES_KEPT) extra = ring + 1;
            if (extra >= 0 && ring >= extra) break;
        }
        return out;
    }
}
