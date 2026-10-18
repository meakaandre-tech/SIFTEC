package com.meakaandre.siftec.geyser;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.map.MapTerrain;
import com.meakaandre.siftec.node.NodeMap;
import com.meakaandre.siftec.node.Terrain;
import com.meakaandre.siftec.save.JsonSavedData;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Finds the sulfur geysers (vanilla's potent sulfur) in land nobody has loaded, for the Radar Tower's sweep and the
 * Object Scanner. Geysers are placed by the game's own cave features, so they cannot be worked out from the seed the
 * way nodes and crash sites are: a search samples the generator's biomes on the worker thread for the chunks with sulfur
 * caves under them, then has the game generate just those chunks and their neighbours (to the features step, in the
 * background, a few at a time, never loading them for play) and reads the potent sulfur out of them. What it finds goes
 * on the map ({@link MapTerrain#addFound}); every chunk it has read is remembered, so it is never generated for this
 * again.
 */
public final class GeyserSearch {
    /** Chunks generated at the same time, and chunks of biome sampling a worker piece takes. */
    private static final int AT_ONCE = 4, SAMPLE_BATCH = 64;
    /** Heights where sulfur caves are looked for (the survey's): every 8 blocks from -60 to 100. */
    private static final int Y_FROM = -60, Y_TO = 100, Y_STEP = 8;

    /** A search under way: the chunks still to generate, what it has found, and who to tell. */
    private static final class Job {
        final LongLinkedOpenHashSet queue = new LongLinkedOpenHashSet();
        final List<BlockPos> found = new ArrayList<>();
        final Consumer<List<BlockPos>> done;
        int running;

        Job(Consumer<List<BlockPos>> done) {
            this.done = done;
        }
    }

    private static final List<Job> JOBS = new ArrayList<>();
    /** Chunks being generated now (so two searches never ask for the same one twice at once). */
    private static final LongOpenHashSet RUNNING = new LongOpenHashSet();

    private GeyserSearch() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(GeyserSearch::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            JOBS.clear();
            RUNNING.clear();
        });
    }

    /** The chunks already read for geysers, kept with the world. */
    public static class Searched extends JsonSavedData<Searched.Stored> {
        public static class Stored {
            List<Long> chunks = new ArrayList<>();
        }

        static final Codec<Searched> CODEC = JsonSavedData.codec(Searched::new);
        static final SavedDataType<Searched> TYPE = new SavedDataType<>(Siftec.id("geyser_search"), Searched::new, CODEC, null);
        private transient LongOpenHashSet set;

        public Searched() {
            super("geyser_search", Stored.class, Stored::new);
        }

        static Searched get(MinecraftServer server) {
            return server.getDataStorage().computeIfAbsent(TYPE);
        }

        LongOpenHashSet set() {
            if (set == null) set = new LongOpenHashSet(stored.chunks);
            return set;
        }

        boolean has(long chunk) {
            return set().contains(chunk);
        }

        void add(long chunk) {
            if (set().add(chunk)) {
                stored.chunks.add(chunk);
                setDirty();
            }
        }

        /** For the selftest: forgets the chunks in a square, so a search reads them again. */
        void forget(int cx0, int cz0, int cx1, int cz1) {
            stored.chunks.removeIf(c -> {
                int x = ChunkPos.getX(c), z = ChunkPos.getZ(c);
                return x >= cx0 && x <= cx1 && z >= cz0 && z <= cz1;
            });
            set = null;
            setDirty();
        }
    }

    /** True if a chunk has sulfur caves under it, as the generator gives the biome (worker thread). */
    public static boolean sulfurBelow(ServerLevel level, int cx, int cz) {
        for (int y = Y_FROM; y <= Y_TO; y += Y_STEP) {
            for (int[] d : new int[][]{{4, 4}, {12, 12}, {4, 12}, {12, 4}}) {
                if (Terrain.biome(level, (cx << 4) + d[0], y, (cz << 4) + d[1]).unwrapKey()
                    .map(k -> k.identifier().getPath().equals("sulfur_caves")).orElse(false)) return true;
            }
        }
        return false;
    }

    /**
     * Searches the chunks within {@code radius} chunks of a block for geysers (the overworld only, where sulfur caves
     * are). {@code done} gets, on the server thread, every geyser found in chunks read by this search; chunks read by an
     * earlier one are skipped, their geysers already on the map.
     */
    public static void search(ServerLevel level, BlockPos centre, int radius, Consumer<List<BlockPos>> done) {
        if (level.dimension() != Level.OVERWORLD) {
            done.accept(List.of());
            return;
        }
        Searched searched = Searched.get(level.getServer());
        int ccx = centre.getX() >> 4, ccz = centre.getZ() >> 4;
        List<Long> chunks = new ArrayList<>();
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                long c = ChunkPos.pack(ccx + dx, ccz + dz);
                if (!searched.has(c)) chunks.add(c);
            }
        }
        if (chunks.isEmpty()) {
            done.accept(List.of());
            return;
        }
        LongArrayList caves = new LongArrayList();
        NodeMap.batched(level.getServer(), chunks, SAMPLE_BATCH, batch -> {
            LongArrayList out = new LongArrayList();
            for (long c : batch) if (sulfurBelow(level, ChunkPos.getX(c), ChunkPos.getZ(c))) out.add(c);
            return out;
        }, part -> {
            if (part != null) caves.addAll(part);
        }, () -> {
            // a chunk with sulfur caves, and its neighbours (a spring or pool spills over the chunk's edge)
            Job job = new Job(done);
            for (long c : caves) {
                int x = ChunkPos.getX(c), z = ChunkPos.getZ(c);
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) job.queue.add(ChunkPos.pack(x + dx, z + dz));
            }
            // the chunks with no caves that were sampled are read too: there is nothing in them
            for (long c : chunks) if (!job.queue.contains(c)) searched.add(c);
            job.queue.removeIf(searched::has);
            Siftec.LOGGER.info("SIFTEC geysers: search round {} r{}: {} chunks sampled, {} with sulfur caves, {} to generate",
                centre.toShortString(), radius, chunks.size(), caves.size(), job.queue.size());
            if (job.queue.isEmpty()) done.accept(List.of());
            else JOBS.add(job);
        });
    }

    private static void tick(MinecraftServer server) {
        if (JOBS.isEmpty()) return;
        ServerLevel level = server.overworld();
        Searched searched = Searched.get(server);
        for (Job job : new ArrayList<>(JOBS)) {
            while (job.running < AT_ONCE && !job.queue.isEmpty()) {
                long c = job.queue.removeFirstLong();
                if (searched.has(c) || !RUNNING.add(c)) continue;
                job.running++;
                int x = ChunkPos.getX(c), z = ChunkPos.getZ(c);
                level.getChunkSource().getChunkFuture(x, z, ChunkStatus.FEATURES, true).whenCompleteAsync((result, error) -> {
                    RUNNING.remove(c);
                    job.running--;
                    ChunkAccess chunk = error == null && result != null ? result.orElse(null) : null;
                    if (chunk != null) {
                        List<BlockPos> here = read(level, chunk);
                        job.found.addAll(here);
                        MapTerrain.addFound(level, x, z, here);
                    }
                    searched.add(c);
                }, server);
            }
            if (job.queue.isEmpty() && job.running == 0) {
                JOBS.remove(job);
                Siftec.LOGGER.info("SIFTEC geysers: search done, {} geysers found", job.found.size());
                job.done.accept(List.copyOf(job.found));
            }
        }
    }

    /** The potent sulfur in a chunk: whole sections without any are skipped by their palette. */
    static List<BlockPos> read(ServerLevel level, ChunkAccess chunk) {
        List<BlockPos> out = new ArrayList<>();
        LevelChunkSection[] sections = chunk.getSections();
        int cx = chunk.getPos().x(), cz = chunk.getPos().z();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(s -> s.is(Blocks.POTENT_SULFUR))) continue;
            int baseY = level.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.is(Blocks.POTENT_SULFUR)) out.add(new BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z));
                    }
                }
            }
        }
        return out;
    }

    /** For the selftest: forgets the chunks in a square round a block. */
    public static void forget(MinecraftServer server, BlockPos centre, int radius) {
        int cx = centre.getX() >> 4, cz = centre.getZ() >> 4;
        Searched.get(server).forget(cx - radius, cz - radius, cx + radius, cz + radius);
    }

    /** True while a search is still generating chunks. */
    public static boolean busy() {
        return !JOBS.isEmpty();
    }
}
