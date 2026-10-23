package com.meakaandre.siftec.map;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.drone.DronePortBlockEntity;
import com.meakaandre.siftec.hub.HubBlockEntity;
import com.meakaandre.siftec.node.Terrain;
import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.registry.ModBlocks;
import com.zurrtum.create.content.trains.station.StationBlockEntity;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.PotentSulfurBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Overworld's map terrain on the server, kept low-resolution and on disk ({@code data/siftec/map/terrain}). A
 * chunk is recorded (16 samples: height, the top block's colour, the biome) when it loads and again a few seconds
 * after a block in it changes, and nowhere else: there is no scanning of the world. A Radar Tower's sweep fills
 * land nobody has loaded with a guess from the world generator, which real recording replaces.
 * <p>
 * Chunk recording also finds what the map shows as found in the world: sulfur geysers and the key buildings
 * (HUB, Wormhole Gateway, Radar Towers, Train Stations, Truck Stations, Drone Ports), kept per chunk with the
 * company that owns them.
 * <p>
 * Everything here runs on the server thread except the generator guesses, which run on the node map's worker and
 * are put in on the server thread.
 */
public final class MapTerrain {
    /** Tiles not used for this long are written out and dropped from memory. */
    private static final long IDLE_MS = 5 * 60_000;
    /** Chunks recorded per server tick at most; the rest wait. */
    private static final int PER_TICK = 48;
    /** Ticks a changed chunk waits before it is recorded again, so a busy build is recorded once, not every block. */
    private static final int CHANGE_DELAY = 100;
    /** Ticks between saving tiles and dropping idle ones (a minute). */
    private static final int SAVE_TICKS = 1200;
    /** Colour of a column with nothing in it (the void). */
    private static final int VOID_COLOUR = 0x202020;
    /** Points a chunk's guess samples (2 by 2). */
    private static final int GUESS_POINTS = 4;
    /** Blocks between a guess's sample points, and from the chunk's corner to the first one. */
    private static final int GUESS_SPACING = 8, GUESS_INSET = 4;

    /** A world-found point of interest, with the company owning it ("" for nobody). */
    public record Found(MapPoi poi, String owner) {
    }

    private static final Long2ObjectOpenHashMap<MapTile> TILES = new Long2ObjectOpenHashMap<>();
    private static final Long2LongOpenHashMap USED = new Long2LongOpenHashMap();
    private static final LongOpenHashSet DIRTY = new LongOpenHashSet();
    /** Chunks waiting to be recorded, oldest first. */
    private static final LongLinkedOpenHashSet QUEUE = new LongLinkedOpenHashSet();
    /** Changed chunks and the tick they may be recorded again. */
    private static final Long2LongOpenHashMap CHANGED = new Long2LongOpenHashMap();
    /** Per region: chunk -> what was found in it. */
    private static final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<List<Found>>> FOUND = new Long2ObjectOpenHashMap<>();
    private static final LongOpenHashSet FOUND_LOADED = new LongOpenHashSet();
    /** Regions whose world-found things changed since the last {@link #takeFoundChanges}. */
    private static final LongOpenHashSet FOUND_CHANGED = new LongOpenHashSet();
    private static @Nullable Path root;
    private static int ticks;

    private MapTerrain() {
    }

    public static void start(MinecraftServer server) {
        root = server.getWorldPath(LevelResource.DATA).resolve("siftec").resolve("map");
        try {
            Files.createDirectories(root.resolve("terrain"));
        } catch (IOException e) {
            Siftec.LOGGER.error("SIFTEC map: could not make {}", root, e);
        }
    }

    public static void stop() {
        saveAll();
        TILES.clear();
        USED.clear();
        DIRTY.clear();
        QUEUE.clear();
        CHANGED.clear();
        FOUND.clear();
        FOUND_LOADED.clear();
        FOUND_CHANGED.clear();
        root = null;
    }

    public static @Nullable Path root() {
        return root;
    }

    // ------------------------------------------------------------------------------------------------ tiles

    private static Path tileFile(long region) {
        return root.resolve("terrain").resolve("r." + MapGrid.keyX(region) + "." + MapGrid.keyZ(region) + ".bin");
    }

    private static Path foundFile(long region) {
        return root.resolve("terrain").resolve("f." + MapGrid.keyX(region) + "." + MapGrid.keyZ(region) + ".bin");
    }

    /** The tile, from memory or disk; a blank one if there is none yet. Null only with no world open. */
    public static @Nullable MapTile tile(long region) {
        if (root == null) return null;
        USED.put(region, System.currentTimeMillis());
        MapTile tile = TILES.get(region);
        if (tile != null) return tile;
        Path file = tileFile(region);
        if (Files.exists(file)) {
            try {
                tile = MapTile.unpack(Files.readAllBytes(file));
            } catch (IOException e) {
                Siftec.LOGGER.warn("SIFTEC map: unreadable tile {}, starting it again", file, e);
            }
        }
        if (tile == null) tile = new MapTile();
        TILES.put(region, tile);
        return tile;
    }

    /** The tile's version without loading it into memory for long; 0 if there is none. */
    public static long version(long region) {
        MapTile tile = tile(region);
        return tile == null ? 0 : tile.version;
    }

    private static void changed(long region, MapTile tile) {
        // strictly increasing, so a client that holds an older copy always asks for the new one
        tile.version = Math.max(tile.version + 1, System.currentTimeMillis());
        DIRTY.add(region);
    }

    public static void saveAll() {
        if (root == null) return;
        for (long region : DIRTY) {
            MapTile tile = TILES.get(region);
            if (tile != null) write(tileFile(region), tile.pack());
        }
        DIRTY.clear();
        for (long region : FOUND_LOADED) saveFound(region);
    }

    private static void write(Path file, byte[] data) {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Siftec.LOGGER.error("SIFTEC map: could not write {}", file, e);
        }
    }

    // ------------------------------------------------------------------------------------------------ recording

    /** A chunk loaded: record it soon. */
    public static void loaded(ServerLevel level, LevelChunk chunk) {
        if (root == null || !MapServer.mapped(level)) return;
        QUEUE.add(MapGrid.key(chunk.getPos().x(), chunk.getPos().z()));
    }

    /** A block changed in a chunk: record it again after a while. */
    public static void blockChanged(ServerLevel level, BlockPos pos) {
        if (root == null || !MapServer.mapped(level)) return;
        long key = MapGrid.key(pos.getX() >> 4, pos.getZ() >> 4);
        if (!CHANGED.containsKey(key)) CHANGED.put(key, level.getServer().getTickCount() + CHANGE_DELAY);
    }

    public static void tick(ServerLevel level) {
        if (root == null) return;
        int now = level.getServer().getTickCount();
        if (!CHANGED.isEmpty()) {
            var it = CHANGED.long2LongEntrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (e.getLongValue() > now) continue;
                QUEUE.add(e.getLongKey());
                it.remove();
            }
        }
        for (int n = 0; n < PER_TICK && !QUEUE.isEmpty(); n++) {
            long key = QUEUE.removeFirstLong();
            LevelChunk chunk = level.getChunkSource().getChunkNow(MapGrid.keyX(key), MapGrid.keyZ(key));
            if (chunk != null) record(level, chunk);
        }
        if (++ticks % SAVE_TICKS == 0) {
            saveAll();
            long cutoff = System.currentTimeMillis() - IDLE_MS;
            List<Long> idle = new ArrayList<>();
            USED.long2LongEntrySet().forEach(e -> {
                if (e.getLongValue() < cutoff) idle.add(e.getLongKey());
            });
            for (long region : idle) {
                USED.remove(region);
                TILES.remove(region);
                if (FOUND_LOADED.remove(region)) FOUND.remove(region);
            }
        }
    }

    /** Records a loaded chunk's 16 pixels and what stands in it. */
    public static void record(ServerLevel level, LevelChunk chunk) {
        int cx = chunk.getPos().x(), cz = chunk.getPos().z();
        long region = MapGrid.regionOfChunk(cx, cz);
        MapTile tile = tile(region);
        if (tile == null) return;
        boolean any = false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = Math.floorMod(cx, MapGrid.REGION_CHUNKS) * MapGrid.CHUNK_PX, baseZ = Math.floorMod(cz, MapGrid.REGION_CHUNKS) * MapGrid.CHUNK_PX;
        int min = level.getMinY();
        for (int pz = 0; pz < MapGrid.CHUNK_PX; pz++) {
            for (int px = 0; px < MapGrid.CHUNK_PX; px++) {
                int lx = px * MapGrid.PX_BLOCKS + MapGrid.PX_BLOCKS / 2, lz = pz * MapGrid.PX_BLOCKS + MapGrid.PX_BLOCKS / 2;
                int x = (cx << 4) + lx, z = (cz << 4) + lz;
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                int colour, height;
                if (top <= min) {
                    colour = VOID_COLOUR | MapTile.KNOWN;
                    height = min;
                } else {
                    pos.set(x, top, z);
                    BlockState state = chunk.getBlockState(pos);
                    Holder<Biome> biome = level.getBiome(pos);
                    if (state.getFluidState().is(FluidTags.WATER)) {
                        int floor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, lx, lz);
                        colour = MapPalette.water(top - floor) | MapTile.KNOWN | MapTile.WATER;
                    } else {
                        int block = state.getMapColor(level, pos).col;
                        colour = MapPalette.ground(MapPalette.biome(biome), block) | MapTile.KNOWN;
                    }
                    height = top;
                }
                int i = MapTile.index(baseX + px, baseZ + pz);
                if (tile.colour[i] != colour || tile.height[i] != height) {
                    tile.colour[i] = colour;
                    tile.height[i] = (short) height;
                    any = true;
                }
            }
        }
        if (any) changed(region, tile);
        recordFound(level, chunk, region);
    }

    private static void recordFound(ServerLevel level, LevelChunk chunk, long region) {
        List<Found> found = new ArrayList<>();
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            MapPoi.Kind kind = null;
            String owner = "";
            BlockPos p = be.getBlockPos();
            if (be instanceof HubBlockEntity hub) {
                if (!hub.prefab) continue;
                kind = be.getBlockState().is(ModBlocks.WORMHOLE_GATEWAY.get()) ? MapPoi.Kind.GATEWAY : MapPoi.Kind.HUB;
                owner = hub.companyId;
            } else if (be instanceof ControllerBlockEntity c) {
                if (c.buildingId.equals("radar_tower")) kind = MapPoi.Kind.RADAR_TOWER;
                else if (c.buildingId.equals("truck_station")) kind = MapPoi.Kind.TRUCK_STATION;
                owner = c.companyId;
            } else if (be instanceof DronePortBlockEntity port) {
                kind = MapPoi.Kind.DRONE_PORT;
                owner = port.companyId;
            } else if (be instanceof StationBlockEntity) {
                kind = MapPoi.Kind.TRAIN_STATION;
                String id = be.getAttached(Ownership.OWNER);
                owner = id == null ? "" : id;
            } else if (be instanceof PotentSulfurBlockEntity) {
                kind = MapPoi.Kind.GEYSER;
            }
            if (kind != null) found.add(new Found(new MapPoi(kind, "", 0, p.getX(), p.getY(), p.getZ()), owner == null ? "" : owner));
        }
        Long2ObjectOpenHashMap<List<Found>> byChunk = found(region);
        long key = MapGrid.key(chunk.getPos().x(), chunk.getPos().z());
        List<Found> before = byChunk.get(key);
        if (found.isEmpty() ? before == null || before.isEmpty() : found.equals(before)) return;
        if (found.isEmpty()) byChunk.remove(key);
        else byChunk.put(key, found);
        FOUND_CHANGED.add(region);
    }

    /**
     * Geysers found in a chunk that is not loaded (a Radar Tower's or the Object Scanner's search had it generated):
     * they replace the chunk's geysers on record, and anything else found there stays. When the chunk is loaded for real
     * it is recorded as usual.
     */
    public static void addFound(ServerLevel level, int cx, int cz, List<BlockPos> geysers) {
        if (root == null || !MapServer.mapped(level)) return;
        long region = MapGrid.regionOfChunk(cx, cz);
        Long2ObjectOpenHashMap<List<Found>> byChunk = found(region);
        long key = MapGrid.key(cx, cz);
        List<Found> before = byChunk.get(key);
        List<Found> now = new ArrayList<>();
        if (before != null) for (Found f : before) if (f.poi().kind() != MapPoi.Kind.GEYSER) now.add(f);
        for (BlockPos p : geysers) now.add(new Found(new MapPoi(MapPoi.Kind.GEYSER, "", 0, p.getX(), p.getY(), p.getZ()), ""));
        if (now.equals(before == null ? List.of() : before)) return;
        if (now.isEmpty()) byChunk.remove(key);
        else byChunk.put(key, now);
        FOUND_CHANGED.add(region);
    }

    /** Every geyser on record within this many blocks of a spot (the Object Scanner's), nearest first. */
    public static List<BlockPos> geysersNear(int x, int z, int blocks) {
        List<BlockPos> out = new ArrayList<>();
        if (root == null) return out;
        int r = Math.max(0, Math.floorDiv(blocks, MapGrid.REGION_BLOCKS)) + 1;
        long centre = MapGrid.regionOfChunk(x >> 4, z >> 4);
        int rx = MapGrid.keyX(centre), rz = MapGrid.keyZ(centre);
        long max = (long) blocks * blocks;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (List<Found> list : found(MapGrid.key(rx + dx, rz + dz)).values()) {
                    for (Found f : list) {
                        if (f.poi().kind() != MapPoi.Kind.GEYSER) continue;
                        long ddx = f.poi().x() - x, ddz = f.poi().z() - z;
                        if (ddx * ddx + ddz * ddz <= max) out.add(new BlockPos(f.poi().x(), f.poi().y(), f.poi().z()));
                    }
                }
            }
        }
        out.sort(Comparator.comparingLong(p -> (long) (p.getX() - x) * (p.getX() - x) + (long) (p.getZ() - z) * (p.getZ() - z)));
        return out;
    }

    /** What was found in a region, by chunk (loaded from disk the first time). */
    private static Long2ObjectOpenHashMap<List<Found>> found(long region) {
        Long2ObjectOpenHashMap<List<Found>> byChunk = FOUND.get(region);
        if (byChunk != null) return byChunk;
        byChunk = new Long2ObjectOpenHashMap<>();
        FOUND.put(region, byChunk);
        FOUND_LOADED.add(region);
        USED.put(region, System.currentTimeMillis());
        Path file = root == null ? null : foundFile(region);
        if (file != null && Files.exists(file)) {
            try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
                int chunks = in.readInt();
                MapPoi.Kind[] kinds = MapPoi.Kind.values();
                for (int c = 0; c < chunks; c++) {
                    long key = in.readLong();
                    int n = in.readInt();
                    List<Found> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        MapPoi.Kind kind = kinds[Math.floorMod(in.readByte(), kinds.length)];
                        list.add(new Found(new MapPoi(kind, "", 0, in.readInt(), in.readInt(), in.readInt()), in.readUTF()));
                    }
                    byChunk.put(key, list);
                }
            } catch (IOException e) {
                Siftec.LOGGER.warn("SIFTEC map: unreadable {}", file, e);
            }
        }
        return byChunk;
    }

    private static void saveFound(long region) {
        Long2ObjectOpenHashMap<List<Found>> byChunk = FOUND.get(region);
        if (byChunk == null || root == null) return;
        Path file = foundFile(region);
        try (var bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(byChunk.size());
            for (var e : byChunk.long2ObjectEntrySet()) {
                out.writeLong(e.getLongKey());
                out.writeInt(e.getValue().size());
                for (Found f : e.getValue()) {
                    out.writeByte(f.poi().kind().ordinal());
                    out.writeInt(f.poi().x());
                    out.writeInt(f.poi().y());
                    out.writeInt(f.poi().z());
                    out.writeUTF(f.owner());
                }
            }
            out.flush();
            write(file, bytes.toByteArray());
        } catch (IOException e) {
            Siftec.LOGGER.error("SIFTEC map: could not write {}", file, e);
        }
    }

    /** What was found in a region, for one company: geysers and unowned stations for all, buildings only for their owner. */
    public static List<MapPoi> foundFor(long region, String companyId) {
        List<MapPoi> out = new ArrayList<>();
        for (List<Found> list : found(region).values()) {
            for (Found f : list) {
                boolean mine = Companies.same(f.owner(), companyId) || (f.poi().kind() == MapPoi.Kind.GEYSER)
                    || (f.poi().kind() == MapPoi.Kind.TRAIN_STATION && f.owner().isEmpty());
                if (mine) out.add(f.poi());
            }
        }
        return out;
    }

    /** Regions whose world-found things changed since last asked. */
    public static long[] takeFoundChanges() {
        long[] out = FOUND_CHANGED.toLongArray();
        FOUND_CHANGED.clear();
        return out;
    }

    // ------------------------------------------------------------------------------------------------ guesses

    /** A chunk of generator guesses: 2 by 2 samples, each covering 2 by 2 pixels. */
    public record Guess(long chunk, int[] colour, short[] height) {
    }

    /** True if the chunk has nothing recorded or only a guess (so a guess may fill it). */
    public static boolean needsGuess(int cx, int cz) {
        MapTile tile = tile(MapGrid.regionOfChunk(cx, cz));
        if (tile == null) return false;
        int i = MapTile.index(Math.floorMod(cx, MapGrid.REGION_CHUNKS) * MapGrid.CHUNK_PX, Math.floorMod(cz, MapGrid.REGION_CHUNKS) * MapGrid.CHUNK_PX);
        return !tile.known(i);
    }

    /** Off the server thread: the generator's ground for a chunk, at 2 by 2 points. */
    public static Guess guess(ServerLevel level, int cx, int cz, int seaLevel) {
        int[] colour = new int[GUESS_POINTS];
        short[] height = new short[GUESS_POINTS];
        for (int s = 0; s < GUESS_POINTS; s++) {
            int x = (cx << 4) + (s & 1) * GUESS_SPACING + GUESS_INSET, z = (cz << 4) + (s >> 1) * GUESS_SPACING + GUESS_INSET;
            Terrain.Column col = Terrain.column(level, x, z, false);
            if (col == null) {
                colour[s] = VOID_COLOUR | MapTile.KNOWN | MapTile.GUESSED;
                height[s] = (short) level.getMinY();
            } else if (col.y() < seaLevel - 1) {
                colour[s] = MapPalette.water(seaLevel - 1 - col.y()) | MapTile.KNOWN | MapTile.GUESSED | MapTile.WATER;
                height[s] = (short) (seaLevel - 1);
            } else {
                colour[s] = MapPalette.guess(col.biome()) | MapTile.KNOWN | MapTile.GUESSED;
                height[s] = (short) col.y();
            }
        }
        return new Guess(MapGrid.key(cx, cz), colour, height);
    }

    /** On the server thread: puts guesses in where nothing real has been recorded. Returns the regions changed. */
    public static LongOpenHashSet apply(List<Guess> guesses) {
        LongOpenHashSet changed = new LongOpenHashSet();
        for (Guess g : guesses) {
            int cx = MapGrid.keyX(g.chunk()), cz = MapGrid.keyZ(g.chunk());
            long region = MapGrid.regionOfChunk(cx, cz);
            MapTile tile = tile(region);
            if (tile == null) continue;
            int baseX = Math.floorMod(cx, MapGrid.REGION_CHUNKS) * MapGrid.CHUNK_PX, baseZ = Math.floorMod(cz, MapGrid.REGION_CHUNKS) * MapGrid.CHUNK_PX;
            boolean any = false;
            for (int pz = 0; pz < MapGrid.CHUNK_PX; pz++) {
                for (int px = 0; px < MapGrid.CHUNK_PX; px++) {
                    int i = MapTile.index(baseX + px, baseZ + pz);
                    if (tile.known(i) && !tile.guessed(i)) continue;
                    int s = (pz >= MapGrid.CHUNK_PX / 2 ? 2 : 0) + (px >= MapGrid.CHUNK_PX / 2 ? 1 : 0);
                    tile.colour[i] = g.colour()[s];
                    tile.height[i] = g.height()[s];
                    any = true;
                }
            }
            if (any) {
                changed(region, tile);
                changed.add(region);
            }
        }
        return changed;
    }
}
