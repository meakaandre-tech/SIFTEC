package com.meakaandre.siftec.client.map;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.map.MapGrid;
import com.meakaandre.siftec.map.MapTile;
import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The client's map tiles: kept on disk per map ({@code siftec/map/<map id>/} in the game folder) so a world's map is
 * there at once on the next visit and only changed tiles are sent again, and turned into textures for the screen in
 * the manner of Satisfactory's map: soft ground colours, light from the north-west, contour lines every 16 blocks
 * (darker every 64), and the fog of war over what the company has not explored.
 */
public final class ClientTiles {
    /** Fog: a dark blue-grey with a faint diagonal weave. */
    private static final int FOG = 0x2A3440, FOG_LINE = 0x313C4A;
    /** Explored but not recorded yet. */
    private static final int UNKNOWN = 0x5A626A;

    private static final class Entry {
        @Nullable MapTile tile;
        @Nullable DynamicTexture texture;
        @Nullable Identifier id;
        boolean stale = true;
        long asked;
        boolean fromDisk;
    }

    private static final Long2ObjectOpenHashMap<Entry> TILES = new Long2ObjectOpenHashMap<>();

    private ClientTiles() {
    }

    private static @Nullable Path dir() {
        String id = MapClient.mapId;
        if (id == null) return null;
        return Minecraft.getInstance().gameDirectory.toPath().resolve("siftec").resolve("map").resolve(id);
    }

    private static Entry entry(long region) {
        Entry e = TILES.get(region);
        if (e != null) return e;
        e = new Entry();
        TILES.put(region, e);
        Path dir = dir();
        if (dir != null) {
            Path file = dir.resolve("r." + MapGrid.keyX(region) + "." + MapGrid.keyZ(region) + ".bin");
            if (Files.exists(file)) {
                try {
                    e.tile = MapTile.unpack(Files.readAllBytes(file));
                    e.fromDisk = true;
                } catch (IOException ex) {
                    Siftec.LOGGER.warn("SIFTEC map: unreadable cached tile {}", file, ex);
                }
            }
        }
        return e;
    }

    /** The version held for a region (0 for none). */
    public static long version(long region) {
        Entry e = entry(region);
        return e.tile == null ? 0 : e.tile.version;
    }

    /** True if the region was asked for less than {@code ms} ago; marks it asked otherwise. */
    public static boolean askedWithin(long region, long ms) {
        Entry e = entry(region);
        long now = System.currentTimeMillis();
        if (now - e.asked < ms) return true;
        e.asked = now;
        return false;
    }

    /** A tile came from the server: kept, written to the disk cache, drawn again. */
    public static void accept(long region, byte[] data) {
        MapTile tile;
        try {
            tile = MapTile.unpack(data);
        } catch (IOException e) {
            Siftec.LOGGER.warn("SIFTEC map: bad tile from the server", e);
            return;
        }
        Entry e = entry(region);
        e.tile = tile;
        e.stale = true;
        Path dir = dir();
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("r." + MapGrid.keyX(region) + "." + MapGrid.keyZ(region) + ".bin");
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            Siftec.LOGGER.warn("SIFTEC map: could not cache a tile", ex);
        }
    }

    /** The region's fog changed: drawn again next time it is shown. */
    public static void fogChanged(long region) {
        Entry e = TILES.get(region);
        if (e != null) e.stale = true;
    }

    /** The region's texture, drawn or redrawn as needed; null when nothing is known there. */
    public static @Nullable Identifier texture(long region) {
        Entry e = entry(region);
        long[] fog = MapClient.FOG.get(region);
        if (fog == null) return null;
        if (e.texture == null) {
            e.id = Siftec.id("map/tile_" + Long.toHexString(region));
            e.texture = new DynamicTexture(() -> "siftec map tile", MapGrid.REGION_PX, MapGrid.REGION_PX, false);
            Minecraft.getInstance().getTextureManager().register(e.id, e.texture);
            e.stale = true;
        }
        if (e.stale) {
            NativeImage image = e.texture.getPixels();
            if (image != null) {
                paint(image, e.tile, fog);
                e.texture.upload();
            }
            e.stale = false;
        }
        return e.id;
    }

    /** The height recorded at a block, or null. */
    public static @Nullable Integer height(int x, int z) {
        long region = MapGrid.regionOfBlock(x, z);
        Entry e = TILES.get(region);
        if (e == null || e.tile == null) return null;
        int px = Math.floorMod(x, MapGrid.REGION_BLOCKS) / MapGrid.PX_BLOCKS, pz = Math.floorMod(z, MapGrid.REGION_BLOCKS) / MapGrid.PX_BLOCKS;
        int i = MapTile.index(px, pz);
        return e.tile.known(i) ? (int) e.tile.height[i] : null;
    }

    public static void clear() {
        for (Entry e : TILES.values()) {
            if (e.id != null) Minecraft.getInstance().getTextureManager().release(e.id);
        }
        TILES.clear();
    }

    // ------------------------------------------------------------------------------------------------ drawing

    private static void paint(NativeImage image, @Nullable MapTile tile, long[] fog) {
        int n = MapGrid.REGION_PX;
        for (int pz = 0; pz < n; pz++) {
            for (int px = 0; px < n; px++) {
                int chunkBit = (pz / MapGrid.CHUNK_PX) * MapGrid.REGION_CHUNKS + px / MapGrid.CHUNK_PX;
                int rgb;
                if (!MapGrid.get(fog, chunkBit)) {
                    rgb = ((px + pz) % 6 == 0) ? FOG_LINE : FOG;
                } else if (tile == null || !tile.known(MapTile.index(px, pz))) {
                    rgb = UNKNOWN;
                } else {
                    rgb = shade(tile, px, pz);
                }
                image.setPixel(px, pz, 0xFF000000 | rgb);
            }
        }
    }

    private static int h(MapTile tile, int px, int pz) {
        px = Math.clamp(px, 0, MapGrid.REGION_PX - 1);
        pz = Math.clamp(pz, 0, MapGrid.REGION_PX - 1);
        int i = MapTile.index(px, pz);
        return tile.known(i) ? tile.height[i] : Integer.MIN_VALUE;
    }

    private static boolean water(MapTile tile, int px, int pz) {
        px = Math.clamp(px, 0, MapGrid.REGION_PX - 1);
        pz = Math.clamp(pz, 0, MapGrid.REGION_PX - 1);
        return (tile.colour[MapTile.index(px, pz)] & MapTile.WATER) != 0;
    }

    /** One pixel: its colour, lit from the north-west, with contour lines and shore lines. */
    private static int shade(MapTile tile, int px, int pz) {
        int i = MapTile.index(px, pz);
        int c = tile.colour[i] & 0xFFFFFF;
        int here = tile.height[i];
        boolean wet = (tile.colour[i] & MapTile.WATER) != 0;
        float light = 1f;
        if (!wet) {
            int nw = h(tile, px - 1, pz - 1);
            if (nw != Integer.MIN_VALUE) light += Math.clamp((here - nw) * 0.07f, -0.3f, 0.3f);
            int east = h(tile, px + 1, pz), south = h(tile, px, pz + 1);
            boolean contour = false, major = false;
            for (int o : new int[]{east, south}) {
                if (o == Integer.MIN_VALUE) continue;
                if (Math.floorDiv(o, 16) != Math.floorDiv(here, 16)) contour = true;
                if (Math.floorDiv(o, 64) != Math.floorDiv(here, 64)) major = true;
            }
            if (major) light *= 0.72f;
            else if (contour) light *= 0.86f;
        } else if (!water(tile, px + 1, pz) || !water(tile, px, pz + 1) || !water(tile, px - 1, pz) || !water(tile, px, pz - 1)) {
            // the shore
            light = 0.8f;
        }
        int r = Math.clamp((int) ((c >> 16 & 255) * light), 0, 255);
        int g = Math.clamp((int) ((c >> 8 & 255) * light), 0, 255);
        int b = Math.clamp((int) ((c & 255) * light), 0, 255);
        return r << 16 | g << 8 | b;
    }
}
