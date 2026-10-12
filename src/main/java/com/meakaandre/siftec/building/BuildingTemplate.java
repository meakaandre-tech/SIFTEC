package com.meakaandre.siftec.building;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.registry.ModBlocks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A building's blocks, read from a structure file (the format a Structure Block saves, so a building can be built
 * in game, saved and copied into {@code data/siftec/structure/building/}).
 * <p>
 * The file is authored facing north: the player stands on its south side (largest z) and the building stretches
 * north. Here every cell is turned into the frame the Build Gun uses: x to the right (0 is the middle column), y up
 * from the bottom row, z away from the front edge (0 is the front row).
 * <p>
 * Markers: exactly one {@code siftec:building_controller} (the building's brain), any number of
 * {@code siftec:building_port} (where belts and pipes attach; its {@code facing} points out of the building, and
 * its block entity data may hold {@code Target: [x, y, z]}, the cell it feeds or drains in file coordinates, by
 * default the cell just inside it). Air cells are inside space that must be free when the building goes down;
 * structure voids are not part of the building. Everything else is a member: Create machines (the core), and the
 * shell around them.
 */
public final class BuildingTemplate {
    /** One cell, in the Build Gun frame. {@code target} is set for ports only (also in the Build Gun frame). */
    public record Cell(int x, int y, int z, BlockState state, int @Nullable [] target) {
        public boolean solid() {
            return !state.isAir();
        }
    }

    public final List<Cell> cells;
    public final List<Cell> members;
    public final Cell controller;
    public final int minX, maxX, height, depth;

    private BuildingTemplate(List<Cell> cells, Cell controller) {
        this.cells = Collections.unmodifiableList(cells);
        List<Cell> solid = new ArrayList<>();
        int lx = Integer.MAX_VALUE, hx = Integer.MIN_VALUE, hy = 0, hz = 0;
        for (Cell c : cells) {
            if (c.solid()) solid.add(c);
            lx = Math.min(lx, c.x);
            hx = Math.max(hx, c.x);
            hy = Math.max(hy, c.y);
            hz = Math.max(hz, c.z);
        }
        this.members = Collections.unmodifiableList(solid);
        this.controller = controller;
        this.minX = lx;
        this.maxX = hx;
        this.height = hy + 1;
        this.depth = hz + 1;
    }

    // ------------------------------------------------------------------------------------------------ loading

    private static final Map<String, BuildingTemplate> CACHE = new HashMap<>();
    private static final Map<String, String> PROBLEMS = new HashMap<>();

    /** The building's template, read once from the mod jar; null (with the reason logged) if it is missing or broken. */
    public static synchronized @Nullable BuildingTemplate of(BuildingDef def) {
        if (CACHE.containsKey(def.id())) return CACHE.get(def.id());
        BuildingTemplate t = null;
        try (InputStream in = Siftec.class.getResourceAsStream(def.templatePath())) {
            if (in == null) {
                PROBLEMS.put(def.id(), "no file at " + def.templatePath());
            } else {
                t = read(NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap()), def.id());
            }
        } catch (Exception e) {
            PROBLEMS.put(def.id(), e.toString());
        }
        if (t == null) Siftec.LOGGER.error("SIFTEC: building {} has no usable template: {}", def.id(), PROBLEMS.get(def.id()));
        CACHE.put(def.id(), t);
        return t;
    }

    public static synchronized String problem(String id) {
        return PROBLEMS.getOrDefault(id, "");
    }

    static @Nullable BuildingTemplate read(CompoundTag tag, String id) {
        ListTag size = tag.getListOrEmpty("size");
        int sx = size.getIntOr(0, 0), sz = size.getIntOr(2, 0);
        ListTag palette = tag.getListOrEmpty("palette");
        if (palette.isEmpty()) palette = tag.getListOrEmpty("palettes").getListOrEmpty(0);
        List<BlockState> states = new ArrayList<>();
        for (int i = 0; i < palette.size(); i++) states.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK, palette.getCompoundOrEmpty(i)));
        int cx = sx / 2;
        List<Cell> cells = new ArrayList<>();
        Cell controller = null;
        int controllers = 0;
        ListTag blocks = tag.getListOrEmpty("blocks");
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag b = blocks.getCompoundOrEmpty(i);
            ListTag pos = b.getListOrEmpty("pos");
            int index = b.getIntOr("state", -1);
            if (index < 0 || index >= states.size()) continue;
            BlockState state = states.get(index);
            if (state.is(Blocks.STRUCTURE_VOID)) continue;
            int nx = pos.getIntOr(0, 0), ny = pos.getIntOr(1, 0), nz = pos.getIntOr(2, 0);
            int[] target = null;
            if (state.is(ModBlocks.BUILDING_PORT.get())) {
                int[] t = b.getCompoundOrEmpty("nbt").getIntArray("Target").orElse(null);
                if (t != null && t.length == 3) target = new int[]{t[0] - cx, t[1], sz - 1 - t[2]};
            }
            Cell cell = new Cell(nx - cx, ny, sz - 1 - nz, state, target);
            if (state.is(ModBlocks.BUILDING_CONTROLLER.get())) {
                controller = cell;
                controllers++;
            }
            cells.add(cell);
        }
        if (controllers != 1) {
            java.util.Set<String> kinds = new java.util.TreeSet<>();
            for (BlockState st : states) kinds.add(BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());
            PROBLEMS.put(id, controllers + " controllers (needs exactly one siftec:building_controller); read " + blocks.size() + " blocks, "
                + palette.size() + " palette entries " + kinds + ", size " + size + ", first palette entry " + (palette.isEmpty() ? "-" : palette.getCompoundOrEmpty(0)));
            return null;
        }
        return new BuildingTemplate(cells, controller);
    }

    /** Drops the cache (the selftest reads templates fresh). */
    public static synchronized void forget() {
        CACHE.clear();
        PROBLEMS.clear();
    }
}
