package com.meakaandre.siftec.building;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.drone.DronePortBlockEntity;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.machine.ProcessorBlock;
import com.meakaandre.siftec.machine.ProcessorBlockEntity;
import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.place.Places;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.save.JsonSavedData;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an old world's single-block machines into the Build Gun buildings that replaced them, the first time each is
 * seen (it ticks, or someone uses it):
 * <ul>
 * <li>the old Converter and Particle Accelerator (siftec:converter, siftec:particle_accelerator) become the buildings
 * of the same names (review fixes 4), built for free where the old block stood, or nearby if that spot has no room;
 * what the old one held is dropped where it stood. Where no spot nearby has room, the block is taken away and the
 * building's cost is dropped there instead. Neither makes anything in code any more;</li>
 * <li>an old single-block Drone Port becomes the Drone Port building, its terminal where the old block stood (or the
 * building as near as it fits), keeping its company, drone, packages, fire charges and the trip under way. Every port
 * whose drone was set to fly to it flies to the new terminal ({@link #moved}); with no room anywhere near, the port is
 * taken away and its contents, drone and the building's cost dropped there.</li>
 * </ul>
 * A building put down this way gives its cost back when dismantled, as one bought with the Build Gun does.
 */
public final class LegacyConversion {
    /** How far from the old block a building may go, in blocks each way, and the steps it is tried at. */
    private static final int REACH = 24, STEP = 4;
    /** Conversions done a tick. */
    private static final int PER_TICK = 2;
    private static final Map<ResourceKey<Level>, LinkedHashMap<Long, Integer>> QUEUE = new HashMap<>();

    private LegacyConversion() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(LegacyConversion::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> QUEUE.clear());
    }

    /** Old Drone Port terminals moved into buildings: old position to new, kept with the world. */
    public static class Moved extends JsonSavedData<Moved.Stored> {
        public static class Stored {
            Map<String, Long> moved = new HashMap<>();
        }

        static final Codec<Moved> CODEC = JsonSavedData.codec(Moved::new);
        static final SavedDataType<Moved> TYPE = new SavedDataType<>(Siftec.id("legacy_moved"), Moved::new, CODEC, null);

        public Moved() {
            super("legacy_moved", Stored.class, Stored::new);
        }

        static Moved get(MinecraftServer server) {
            return server.getDataStorage().computeIfAbsent(TYPE);
        }
    }

    /** Where an old port's terminal went, or the position itself if it never moved. */
    public static BlockPos moved(MinecraftServer server, BlockPos pos) {
        Long to = Moved.get(server).stored.moved.get(String.valueOf(pos.asLong()));
        return to == null ? pos : BlockPos.of(to);
    }

    /** Asks for an old block to be converted soon (on the server thread, a couple a tick). */
    public static void queue(ServerLevel level, BlockPos pos) {
        QUEUE.computeIfAbsent(level.dimension(), k -> new LinkedHashMap<>()).putIfAbsent(pos.asLong(), 0);
    }

    private static void tick(MinecraftServer server) {
        if (QUEUE.isEmpty()) return;
        int done = 0;
        for (Map.Entry<ResourceKey<Level>, LinkedHashMap<Long, Integer>> e : QUEUE.entrySet()) {
            ServerLevel level = server.getLevel(e.getKey());
            if (level == null) {
                e.getValue().clear();
                continue;
            }
            var it = e.getValue().entrySet().iterator();
            while (it.hasNext() && done < PER_TICK) {
                var entry = it.next();
                BlockPos pos = BlockPos.of(entry.getKey());
                if (!level.isLoaded(pos)) {
                    // its land went away before its turn: it is queued again when it next ticks
                    it.remove();
                    continue;
                }
                Result r = convert(level, pos);
                if (r == Result.LATER && entry.getValue() < 200) {
                    // the land a building would take is not all loaded yet: try again in a while
                    entry.setValue(entry.getValue() + 1);
                    continue;
                }
                it.remove();
                done++;
            }
        }
        QUEUE.values().removeIf(Map::isEmpty);
    }

    /** What happened to an old block. */
    public enum Result {
        /** It is now the building. */
        CONVERTED,
        /** No room anywhere near: taken away, its cost (and what it held) dropped where it stood. */
        REMOVED,
        /** Not every chunk the building would take is loaded yet. */
        LATER,
        /** Nothing old stands there (already converted, or something else). */
        NONE
    }

    /** Converts the old block at a spot now, if there is one. */
    public static Result convert(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (level.getBlockState(pos).getBlock() instanceof ProcessorBlock && be instanceof ProcessorBlockEntity old) return processor(level, pos, old);
        if (level.getBlockState(pos).is(ModBlocks.DRONE_PORT.get()) && be instanceof DronePortBlockEntity port && BuildingIndex.controllerOf(level, pos) == null) {
            return dronePort(level, pos, port);
        }
        return Result.NONE;
    }

    // ------------------------------------------------------------------------------------------------ the processors

    private static Result processor(ServerLevel level, BlockPos pos, ProcessorBlockEntity old) {
        BuildingDef def = Buildings.def(old.machine());
        BuildingTemplate t = def == null ? null : BuildingTemplate.of(def);
        if (def == null || t == null) return Result.NONE;
        Company company = Ownership.of(old);
        // room for the building with the old block gone: the middle of its front edge where the old block stood first
        Placement spot = find(level, def, t, pos, null);
        if (spot == LATER) return Result.LATER;
        List<ItemStack> held = new ArrayList<>(old.takeAll());
        held.addAll(Buildings.fluids(level, pos));
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Result result = put(level, def, spot, company, pos);
        for (ItemStack stack : held) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
        return result;
    }

    // ------------------------------------------------------------------------------------------------ the Drone Port

    private static Result dronePort(ServerLevel level, BlockPos pos, DronePortBlockEntity old) {
        BuildingDef def = Buildings.def("drone_port");
        BuildingTemplate t = def == null ? null : BuildingTemplate.of(def);
        int[] terminal = t == null ? null : terminalCell(t);
        if (def == null || t == null || terminal == null) return Result.NONE;
        Placement spot = find(level, def, t, pos, terminal);
        if (spot == LATER) return Result.LATER;
        Company company = old.companyId.isEmpty() ? Ownership.of(old) : CompanyData.get(level.getServer()).byId(old.companyId);
        String name = placeName(level, pos);
        DronePortBlockEntity.Snapshot snap = old.takeSnapshot();
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Result result = put(level, def, spot, company, pos);
        BlockPos now = spot == null ? null : spot.at(terminal[0], terminal[1], terminal[2]);
        if (result == Result.CONVERTED && now != null && level.getBlockEntity(now) instanceof DronePortBlockEntity port) {
            port.apply(snap);
            if (port.companyId.isEmpty() && company != null) port.companyId = company.id;
            port.setChanged();
            if (!port.companyId.isEmpty()) Places.add(level, now, Places.DRONE_PORT, port.companyId, name != null ? name : "Port " + now.getX() + ", " + now.getZ());
            if (!now.equals(pos)) {
                Moved moved = Moved.get(level.getServer());
                moved.stored.moved.put(String.valueOf(pos.asLong()), now.asLong());
                moved.setDirty();
            }
            Siftec.LOGGER.info("SIFTEC: old Drone Port at {} is now the Drone Port building, its terminal at {}", pos.toShortString(), now.toShortString());
        } else {
            // no room: everything it held, and its drone, are dropped with the building's cost
            List<ItemStack> held = new ArrayList<>();
            for (ItemStack s : snap.slots()) if (!s.isEmpty()) held.add(s);
            held.addAll(snap.cargo());
            if (snap.hasDrone()) held.add(new ItemStack(com.meakaandre.siftec.registry.ModItems.CARDBOARD_DRONE.get()));
            for (ItemStack stack : held) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
        }
        return result;
    }

    /** The Drone Port template's terminal cell (the Drone Port block under its pad), in the Build Gun frame. */
    public static int @Nullable [] terminalCell(BuildingTemplate t) {
        for (BuildingTemplate.Cell cell : t.members) if (cell.state().is(ModBlocks.DRONE_PORT.get())) return new int[]{cell.x(), cell.y(), cell.z()};
        return null;
    }

    /** The placement that puts a Drone Port's terminal at a spot, facing one way. */
    public static Placement forTerminal(BuildingDef def, int[] terminal, BlockPos at, Direction facing) {
        Direction right = facing.getClockWise();
        BlockPos anchor = at.offset(-right.getStepX() * terminal[0] - facing.getStepX() * terminal[2], -terminal[1],
            -right.getStepZ() * terminal[0] - facing.getStepZ() * terminal[2]);
        return new Placement(def, anchor, facing, false);
    }

    private static @Nullable String placeName(ServerLevel level, BlockPos pos) {
        return Places.nameAt(level, pos);
    }

    // ------------------------------------------------------------------------------------------------ finding room

    /** Stands for "try again later" in {@link #find}. */
    private static final Placement LATER = new Placement(null, BlockPos.ZERO, Direction.NORTH, false);

    /**
     * The nearest placement of the building that fits, the old block's own cell counted as free: the special cell (a
     * Drone Port's terminal; else the middle of the front edge) on the old block first, then at spots round it every
     * {@link #STEP} blocks out to {@link #REACH}, at the same height or one up. Null if none fits; {@link #LATER} if
     * some of the land it needs is not loaded.
     */
    private static @Nullable Placement find(ServerLevel level, BuildingDef def, BuildingTemplate t, BlockPos old, int @Nullable [] cell) {
        List<int[]> offsets = new ArrayList<>();
        for (int r = 0; r <= REACH; r += STEP) {
            for (int dx = -r; dx <= r; dx += STEP) {
                for (int dz = -r; dz <= r; dz += STEP) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    offsets.add(new int[]{dx, 0, dz});
                    offsets.add(new int[]{dx, 1, dz});
                }
            }
        }
        offsets.sort((a, b) -> Integer.compare(a[0] * a[0] + a[2] * a[2] + a[1], b[0] * b[0] + b[2] * b[2] + b[1]));
        boolean unloaded = false;
        for (int[] o : offsets) {
            BlockPos at = old.offset(o[0], o[1], o[2]);
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                Placement p = cell != null ? forTerminal(def, cell, at, facing)
                    : new Placement(def, at.relative(facing.getOpposite(), t.depth / 2), facing, false);
                int fit = fits(level, p, t, old);
                if (fit == 1) return p;
                if (fit == -1) unloaded = true;
            }
        }
        return unloaded ? LATER : null;
    }

    /** 1 if every cell of the building is free (the old block's counts as free), 0 if not, -1 if some is not loaded. */
    private static int fits(ServerLevel level, Placement p, BuildingTemplate t, BlockPos old) {
        if (level.isOutsideBuildHeight(p.anchor().getY()) || level.isOutsideBuildHeight(p.anchor().getY() + t.height - 1)) return 0;
        for (BuildingTemplate.Cell cell : t.cells) {
            BlockPos pos = p.at(cell);
            if (pos.equals(old)) continue;
            if (!level.isLoaded(pos)) return -1;
            if (!level.getBlockState(pos).canBeReplaced() || BuildingIndex.isMember(level, pos)) return 0;
        }
        return 1;
    }

    /**
     * Puts the building down for free at the placement (the old block is already gone) and makes its refund its cost;
     * with no placement, drops the cost where the old block stood.
     */
    private static Result put(ServerLevel level, BuildingDef def, @Nullable Placement spot, @Nullable Company company, BlockPos old) {
        List<ItemStack> cost = costStacks(def);
        if (spot != null) {
            Buildings.Check check = Buildings.place(level, spot, company, null, true);
            if (check.ok() && level.getBlockEntity(spot.at(BuildingTemplate.of(def).controller)) instanceof ControllerBlockEntity c) {
                c.refund.clear();
                c.refund.addAll(cost);
                c.setChanged();
                Siftec.LOGGER.info("SIFTEC: old {} at {} is now the {} building at {}", def.id(), old.toShortString(), def.id(), c.getBlockPos().toShortString());
                return Result.CONVERTED;
            }
            Siftec.LOGGER.warn("SIFTEC: old {} at {}: the building would not go down ({})", def.id(), old.toShortString(), check.message().getString());
        }
        for (ItemStack stack : cost) Containers.dropItemStack(level, old.getX() + 0.5, old.getY() + 0.5, old.getZ() + 0.5, stack);
        Siftec.LOGGER.info("SIFTEC: old {} at {} had no room to become the building; taken away and its cost given back", def.id(), old.toShortString());
        return Result.REMOVED;
    }

    /** A building's cost as item stacks (a tag cost as the first item of the tag). */
    public static List<ItemStack> costStacks(BuildingDef def) {
        List<ItemStack> out = new ArrayList<>();
        for (Milestone.Cost cost : def.cost()) {
            if (!cost.present()) continue;
            ItemStack one = cost.icon();
            if (one.isEmpty()) continue;
            int left = cost.count();
            while (left > 0) {
                int n = Math.min(left, one.getMaxStackSize());
                out.add(one.copyWithCount(n));
                left -= n;
            }
        }
        return out;
    }
}
