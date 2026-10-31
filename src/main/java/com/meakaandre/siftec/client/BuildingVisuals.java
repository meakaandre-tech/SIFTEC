package com.meakaandre.siftec.client;

import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.BuildingTemplate;
import com.meakaandre.siftec.prefab.Template;
import com.meakaandre.siftec.registry.ModItems;
import com.meakaandre.siftec.vehicle.VehicleKind;
import com.meakaandre.siftec.vehicle.Vehicles;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How a building looks in the build menu and the build hotbar: an icon (the machine that best stands for it, taken
 * from its own template), and the menu's turning 3D preview, drawn as the building's outer blocks stacked as block
 * icons in the game's isometric GUI view, from the back to the front.
 */
final class BuildingVisuals {
    /** One block of the preview: where it sits (turned), and what to draw. */
    private record Voxel(int x, int y, int z, ItemStack stack) {
    }

    /** Block names that stand for a building, best first: the machines inside SIFTEC's buildings. */
    private static final String[] MACHINES = {"mechanical_drill", "mechanical_press", "mechanical_mixer", "encased_fan", "deployer",
        "item_vault", "fluid_tank", "basin", "blaze_burner", "steam_engine", "electric_motor", "generator_coil", "motor", "depot",
        "display_board", "nixie_tube", "lectern", "connector", "smart_chute", "chute"};
    /** Most outer blocks a preview draws (a big building is drawn by its outer shell only, and then cut short). */
    private static final int MAX_VOXELS = 900;
    /** The game's GUI block, per block of the building at a 16 pixel icon: half its width, a quarter-step down per block in, its height. */
    private static final float HALF_W = 7.07f, STEP_DOWN = 3.54f, RISE = 8.66f;
    /** Milliseconds the preview takes for each quarter turn. */
    private static final long TURN_MILLIS = 2500;

    private static final Map<String, ItemStack> ICONS = new HashMap<>();
    private static final Map<String, List<Voxel>> SHELLS = new HashMap<>();

    private BuildingVisuals() {
    }

    /** The building's icon. */
    static ItemStack icon(BuildingDef def) {
        return ICONS.computeIfAbsent(def.id(), id -> pickIcon(def));
    }

    private static ItemStack pickIcon(BuildingDef def) {
        VehicleKind vehicle = Vehicles.kind(def);
        if (vehicle != null) return new ItemStack(Items.MINECART);
        List<BlockState> states = states(def);
        for (String machine : MACHINES) {
            for (BlockState state : states) {
                String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                if (path.contains(machine) && state.getBlock().asItem() != Items.AIR) return new ItemStack(state.getBlock().asItem());
            }
        }
        // no machine in it (a pole, a foundation): its most used block that has an item
        Map<Item, Integer> count = new HashMap<>();
        for (BlockState state : states) {
            Item item = state.getBlock().asItem();
            if (item != Items.AIR) count.merge(item, 1, Integer::sum);
        }
        Item best = count.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        if (best != null) return new ItemStack(best);
        Item own = ModItems.BUILDINGS.get(def.id());
        return own == null ? new ItemStack(ModItems.BUILD_GUN.get()) : new ItemStack(own);
    }

    /** Every block of the building, in its own frame (the HUB and the Gateway at their last level). */
    private static List<BlockState> states(BuildingDef def) {
        List<BlockState> out = new ArrayList<>();
        if (def.structure()) {
            Template t = def.kind().template();
            for (long key : t.keys()) {
                BlockState s = t.at(key, t.maxLevel);
                if (s != null && !s.isAir()) out.add(s);
            }
            return out;
        }
        BuildingTemplate t = BuildingTemplate.of(def);
        if (t != null) for (BuildingTemplate.Cell c : t.members) out.add(c.state());
        return out;
    }

    /** Every block of the building with an item to draw it by, by position. */
    private static List<Voxel> allVoxels(BuildingDef def) {
        List<Voxel> out = new ArrayList<>();
        if (def.structure()) {
            Template t = def.kind().template();
            for (long key : t.keys()) {
                BlockState s = t.at(key, t.maxLevel);
                if (s == null || s.isAir() || s.getBlock().asItem() == Items.AIR) continue;
                BlockPos p = BlockPos.of(key);
                out.add(new Voxel(p.getX(), p.getY(), p.getZ(), new ItemStack(s.getBlock().asItem())));
            }
            return out;
        }
        BuildingTemplate t = BuildingTemplate.of(def);
        if (t == null) return out;
        for (BuildingTemplate.Cell c : t.members) {
            if (c.state().getBlock().asItem() == Items.AIR) continue;
            out.add(new Voxel(c.x(), c.y(), c.z(), new ItemStack(c.state().getBlock().asItem())));
        }
        return out;
    }

    /** The blocks of the building that can be seen from outside: those with an open side. */
    private static List<Voxel> shell(BuildingDef def) {
        return SHELLS.computeIfAbsent(def.id(), id -> {
            List<Voxel> all = allVoxels(def);
            Set<Long> filled = new HashSet<>();
            for (Voxel v : all) filled.add(BlockPos.asLong(v.x, v.y, v.z));
            List<Voxel> out = new ArrayList<>();
            for (Voxel v : all) {
                boolean open = false;
                for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                    if (!filled.contains(BlockPos.asLong(v.x + d[0], v.y + d[1], v.z + d[2]))) open = true;
                }
                if (open) out.add(v);
            }
            // the top first, so a cut-short preview keeps the building's outline
            out.sort(Comparator.comparingInt((Voxel v) -> -v.y));
            return out.size() > MAX_VOXELS ? List.copyOf(out.subList(0, MAX_VOXELS)) : List.copyOf(out);
        });
    }

    /** The building's size: width, depth and height in blocks, or null if it has none (a vehicle has its own). */
    static int @Nullable [] footprint(BuildingDef def) {
        if (def.structure()) {
            Template t = def.kind().template();
            return new int[]{t.maxX - t.minX + 1, t.maxZ - t.minZ + 1, t.maxY - t.minY + 1};
        }
        VehicleKind vehicle = Vehicles.kind(def);
        if (vehicle != null) return new int[]{vehicle.width, vehicle.length, vehicle.height};
        BuildingTemplate t = BuildingTemplate.of(def);
        return t == null ? null : new int[]{t.maxX - t.minX + 1, t.depth, t.height};
    }

    /**
     * Draws the building in the box (x, y, w, h), turning a quarter every few seconds: its outer blocks as the game's
     * block icons, placed on an isometric grid and drawn from the back to the front, scaled to fit. A building with no
     * blocks to draw (a vehicle) shows its icon, large.
     */
    static void preview(GuiGraphicsExtractor g, BuildingDef def, int x, int y, int w, int h, long millis) {
        List<Voxel> shell = shell(def);
        if (shell.isEmpty()) {
            float s = Math.min(w, h) / 16f * 0.6f;
            g.pose().pushMatrix();
            g.pose().translate(x + w / 2f - 8 * s, y + h / 2f - 8 * s);
            g.pose().scale(s, s);
            g.item(icon(def), 0, 0);
            g.pose().popMatrix();
            return;
        }
        int quarter = (int) (millis / TURN_MILLIS % 4);
        List<Voxel> turned = new ArrayList<>(shell.size());
        for (Voxel v : shell) {
            int tx = v.x, tz = v.z;
            for (int i = 0; i < quarter; i++) {
                int nx = -tz;
                tz = tx;
                tx = nx;
            }
            turned.add(new Voxel(tx, v.y, tz, v.stack));
        }
        // its size on screen at one pixel a unit, to scale it into the box
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (Voxel v : turned) {
            float sx = (v.x - v.z) * HALF_W, sy = (v.x + v.z) * STEP_DOWN - v.y * RISE;
            minX = Math.min(minX, sx - 8);
            maxX = Math.max(maxX, sx + 8);
            minY = Math.min(minY, sy - 8);
            maxY = Math.max(maxY, sy + 8);
        }
        float k = Math.min(w / (maxX - minX), h / (maxY - minY));
        k = Math.min(k, 1.5f);
        float ox = x + (w - (maxX - minX) * k) / 2f - minX * k, oy = y + (h - (maxY - minY) * k) / 2f - minY * k;
        // the back first: far rows (small x + z) and low blocks, then nearer and higher
        turned.sort(Comparator.comparingInt((Voxel v) -> v.x + v.z).thenComparingInt(v -> v.y));
        g.enableScissor(x, y, x + w, y + h);
        for (Voxel v : turned) {
            float sx = (v.x - v.z) * HALF_W, sy = (v.x + v.z) * STEP_DOWN - v.y * RISE;
            g.pose().pushMatrix();
            g.pose().translate(ox + (sx - 8) * k, oy + (sy - 8) * k);
            g.pose().scale(k, k);
            g.item(v.stack, 0, 0);
            g.pose().popMatrix();
        }
        g.disableScissor();
    }
}
