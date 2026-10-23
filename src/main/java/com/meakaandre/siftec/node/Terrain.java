package com.meakaandre.siftec.node;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * What the world generator says about a column, without generating anything: whether there is land at all,
 * where its ground is and which biome that ground has. Works on ordinary worlds and on worlds of floating
 * islands over void, where most columns are empty and the generator's sea level is the
 * bottom of the world. Everything here only asks the biome source and the noise of the generator, which the
 * game itself does from its world generation threads, so it may run off the server thread.
 */
public final class Terrain {
    /** The height a column's biome is first sampled at: an ordinary world's surface. */
    public static final int SAMPLE_Y = 64;
    /** The second sample stands this far above the generator's sea level. */
    private static final long SEA_SAMPLE_ABOVE = 8L;
    /** Samples spread from the bottom to the top of the world before a column counts as void. */
    private static final int VOID_SAMPLES = 6;

    /**
     * The top solid block of a column and the biome just above it, and whether water stands over that ground: a sea
     * or river biome, or any lake or pond the generator fills (its surface is above the ground).
     */
    public record Column(int y, Holder<Biome> biome, boolean wet) {
        public Column(int y, Holder<Biome> biome) {
            this(y, biome, false);
        }

        public boolean water() {
            return wet || Terrain.isWater(biome);
        }
    }

    private Terrain() {
    }

    /** A void biome: {@code minecraft:the_void}, or any other mod's {@code *:void}. */
    public static boolean isVoid(Holder<Biome> biome) {
        return biome.unwrapKey().map(key -> {
            String path = key.identifier().getPath();
            return path.equals("void") || path.equals("the_void") || path.endsWith("/void");
        }).orElse(false);
    }

    public static boolean isWater(Holder<Biome> biome) {
        return biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER) || biome.is(BiomeTags.IS_DEEP_OCEAN);
    }

    public static Holder<Biome> biome(ServerLevel level, int x, int y, int z) {
        return level.getUncachedNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
    }

    /**
     * Cheap first test: true if the biome is void everywhere in the column (a handful of samples from the top
     * to the bottom of the world). Ordinary worlds never have a void biome, so this costs them one sample.
     */
    public static boolean voidColumn(ServerLevel level, int x, int z) {
        int min = level.getMinY(), max = level.getMaxY();
        if (!isVoid(biome(level, x, Math.clamp(SAMPLE_Y, min, max), z))) return false;
        if (!isVoid(biome(level, x, Math.clamp(level.getSeaLevel() + SEA_SAMPLE_ABOVE, min, max), z))) return false;
        for (int i = 0; i < VOID_SAMPLES; i++) {
            int y = min + (int) ((max - min) * (i + 0.5) / VOID_SAMPLES);
            if (!isVoid(biome(level, x, y, z))) return false;
        }
        return true;
    }

    /** The ground of a column as the generator makes it (water and plants not counted), or null for an empty or void column. */
    public static @Nullable Column column(ServerLevel level, int x, int z) {
        return column(level, x, z, true);
    }

    /** As {@link #column(ServerLevel, int, int)}; without {@code lakes} only sea and river biomes count as water (one noise sample fewer). */
    public static @Nullable Column column(ServerLevel level, int x, int z, boolean lakes) {
        if (voidColumn(level, x, z)) return null;
        var generator = level.getChunkSource().getGenerator();
        var random = level.getChunkSource().randomState();
        int top = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random) - 1;
        if (top < level.getMinY()) return null;
        Holder<Biome> biome = biome(level, x, top + 1, z);
        if (isVoid(biome)) return null;
        return new Column(top, biome, lakes && wet(level, x, z, top));
    }

    /** True if the generator puts water (or lava) over this column's ground, whose top block is at {@code ground}. */
    public static boolean wet(ServerLevel level, int x, int z, int ground) {
        int surface = level.getChunkSource().getGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState()) - 1;
        return surface > ground;
    }

    /** True if the generator puts water over this column: its surface stands above its ground. */
    public static boolean wet(ServerLevel level, int x, int z) {
        var generator = level.getChunkSource().getGenerator();
        var random = level.getChunkSource().randomState();
        int ground = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
        return generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random) > ground;
    }

    /** True if the point is inside the world border, read on the server thread and handed to a search. */
    public record Border(double minX, double minZ, double maxX, double maxZ) {
        public static Border of(ServerLevel level) {
            var border = level.getWorldBorder();
            return new Border(border.getMinX(), border.getMinZ(), border.getMaxX(), border.getMaxZ());
        }

        public static final Border NONE = new Border(-Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);

        public boolean contains(int x, int z) {
            return x >= minX && x < maxX && z >= minZ && z < maxZ;
        }
    }
}
