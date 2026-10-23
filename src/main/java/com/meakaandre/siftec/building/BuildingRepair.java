package com.meakaandre.siftec.building;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.power.PowerGrids;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps a building's machines whole. A building's controller remembers the shape (its template) its blocks were last
 * checked against. When it loads, and every few seconds after, its blocks are looked over:
 * <ul>
 * <li>a machine that is missing or is the wrong block (a Steam Engine, a coil, a magnet, the Basin, the turbine...) is put
 * back where the template has it, and the log says so;</li>
 * <li>a building whose template has changed since it was built (a save from before the change) is rebuilt to the new
 * shape whole: every block set as the template now has it, and the blocks of the old shape it no longer has taken
 * away, with whatever any of them held dropped at the controller;</li>
 * <li>where something that is not the building's stands in the way, nothing is rebuilt there: the building waits, makes
 * nothing, and says so, until the spot is cleared.</li>
 * </ul>
 * A building never makes up for a missing machine by producing in code: its machines do the work or nothing does. A
 * missing piece of its shell (not a machine) still brings it down, as before ({@link Buildings#missingMember}).
 */
public final class BuildingRepair {
    public enum Result { OK, REBUILT, BLOCKED, WAITING }

    /** Ticks between looks once it has loaded. */
    static final int EVERY = 100;
    private static final TagKey<Block> MAGNETS = TagKey.create(Registries.BLOCK,
        Identifier.fromNamespaceAndPath("create_new_age", "magnet"));

    private BuildingRepair() {
    }

    /** The template's shape as a number: its blocks and where they stand. */
    public static int hash(BuildingTemplate t) {
        int h = 17;
        for (BuildingTemplate.Cell cell : t.members) {
            h = 31 * h + cell.x();
            h = 31 * h + cell.y();
            h = 31 * h + cell.z();
            h = 31 * h + BuiltInRegistries.BLOCK.getKey(cell.state().getBlock()).hashCode();
        }
        return h == 0 ? 1 : h;
    }

    /**
     * A cell of a building's machines rather than its shell: anything with a block entity (Create's and New Age's
     * machines, tanks, the turbine, the connector) but the controller and the ports, the magnets round a generator coil,
     * and the Smelter's lava.
     */
    public static boolean machine(BlockState state) {
        if (state.getBlock() instanceof ControllerBlock || state.getBlock() instanceof PortBlock) return false;
        return state.hasBlockEntity() || state.is(MAGNETS) || state.is(Blocks.LAVA);
    }

    public static Result check(ServerLevel level, ControllerBlockEntity c) {
        Placement p = c.placement();
        BuildingTemplate t = p == null ? null : BuildingTemplate.of(p.def());
        if (t == null || p.def().structure()) return Result.OK;
        for (BuildingTemplate.Cell cell : t.members) if (!level.isLoaded(p.at(cell))) return Result.WAITING;
        int want = hash(t);
        boolean reshape = c.shape != want;
        Paint paint = Paint.parse(c.paint);
        boolean storage = p.def().logic().equals("storage");
        BuildingIndex index = BuildingIndex.get(level);
        BlockPos at = c.getBlockPos().immutable();
        List<BlockPos> rebuilt = new ArrayList<>(), blocked = new ArrayList<>();
        List<ItemStack> spilled = new ArrayList<>();
        Set<Long> cells = new HashSet<>();
        int removed = 0;
        for (BuildingTemplate.Cell cell : t.members) {
            BlockPos pos = p.at(cell);
            cells.add(pos.asLong());
            if (pos.equals(at)) continue;
            BlockState now = level.getBlockState(pos);
            boolean ours = at.equals(BuildingIndex.controllerOf(level, pos));
            if (paint.fits(cell, now.getBlock(), storage)) {
                // on a reshape, a block that only happens to look like the new shape's (a player's) is not taken in: it
                // is in the way like any other
                if (reshape && !ours && !now.isAir()) blocked.add(pos);
                continue;
            }
            // a missing piece of shell on a building of the current shape is left to bring it down
            if (!reshape && !machine(cell.state())) continue;
            if (!ours && !now.isAir() && !now.canBeReplaced()) {
                blocked.add(pos);
                continue;
            }
            if (!now.isAir()) spilled.addAll(Buildings.contents(level, pos));
            level.setBlock(pos, p.orient(paint.look(cell, storage)), Block.UPDATE_ALL);
            if (index != null) index.put(level, pos, at);
            rebuilt.add(pos);
        }
        if (reshape && blocked.isEmpty() && index != null) {
            // the old shape's blocks the new one has no more
            for (BlockPos old : index.members(level, at)) {
                if (cells.contains(old.asLong()) || !level.isLoaded(old)) continue;
                spilled.addAll(Buildings.contents(level, old));
                index.remove(level, old, at);
                if (!level.getBlockState(old).isAir()) level.setBlock(old, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                removed++;
            }
        }
        String name = p.def().id() + " at " + at.toShortString();
        if (!rebuilt.isEmpty() || removed > 0) {
            Buildings.wire(level, p, t, at, c.companyId);
            BuildingLogic.placed(level, c);
            // what the replaced blocks held goes to the company (a crate only its members open), never the Converter's cells
            Buildings.giveBack(level, at.above(), c.companyId, spilled);
            PowerGrids.dirty();
            List<String> what = new ArrayList<>();
            for (BlockPos pos : rebuilt.subList(0, Math.min(6, rebuilt.size()))) {
                what.add(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath() + "@" + pos.toShortString());
            }
            if (reshape) {
                Siftec.LOGGER.info("SIFTEC: building {} was built to an older shape; rebuilt it to the current one: {} blocks set, {} of the old shape taken away, {} stacks it held given back (first set: {})",
                    name, rebuilt.size(), removed, spilled.size(), what);
            } else {
                Siftec.LOGGER.info("SIFTEC: building {} had {} blocks of its machines missing or wrong; rebuilt them from its shape: {}", name, rebuilt.size(), what);
            }
        }
        if (!blocked.isEmpty()) {
            if (!c.broken) {
                BlockPos first = blocked.getFirst();
                Siftec.LOGGER.warn("SIFTEC: building {} cannot rebuild its machines: {} blocks are in the way, the first {} at {}; it makes nothing until they are cleared",
                    name, blocked.size(), BuiltInRegistries.BLOCK.getKey(level.getBlockState(first).getBlock()), first.toShortString());
            }
            c.broken = true;
            c.problem = "siftec.building.blocked";
            c.setChanged();
            return Result.BLOCKED;
        }
        if (c.broken) Siftec.LOGGER.info("SIFTEC: building {} is whole again and runs", name);
        c.broken = false;
        if (c.problem.equals("siftec.building.blocked")) c.problem = "";
        if (reshape) {
            c.shape = want;
            c.setChanged();
        }
        Buildings.reindex(level, c);
        return rebuilt.isEmpty() && removed == 0 ? Result.OK : Result.REBUILT;
    }
}
