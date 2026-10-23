package com.meakaandre.siftec.building;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.blueprint.DesignerBlockEntity;
import com.meakaandre.siftec.death.DeathCrates;
import com.meakaandre.siftec.sim.Factories;
import com.meakaandre.siftec.util.Drops;
import com.meakaandre.siftec.util.Transfers;
import com.zurrtum.create.content.processing.basin.BasinBlockEntity;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Taking a building's blocks down, and a building that broke or lost its controller coming down and giving back. */
public final class BuildingTeardown {
    private BuildingTeardown() {
    }

    /**
     * Unlists and removes every block of a placed building but the controller, emptying containers first, and
     * returns what they held. Blocks that are no longer the building's own (something else stands there now) are
     * left alone.
     */
    static List<ItemStack> takeDown(ServerLevel level, Placement p, BlockPos controllerPos) {
        return takeDown(level, p, controllerPos, Set.of());
    }

    /** As {@link #takeDown(ServerLevel, Placement, BlockPos)}, leaving alone the positions in {@code keep} (a new building's). */
    private static List<ItemStack> takeDown(ServerLevel level, Placement p, BlockPos controllerPos, Set<Long> keep) {
        List<ItemStack> out = new ArrayList<>();
        BuildingTemplate t = BuildingTemplate.of(p.def());
        if (t == null) return out;
        BuildingIndex index = BuildingIndex.get(level);
        List<BlockPos> remove = new ArrayList<>();
        Paint paint = level.getBlockEntity(controllerPos) instanceof ControllerBlockEntity c ? Paint.parse(c.paint) : Paint.NONE;
        boolean storage = p.def().logic() == Logic.STORAGE;
        for (BuildingTemplate.Cell cell : t.members) {
            BlockPos pos = p.at(cell);
            if (keep.contains(pos.asLong())) continue;
            index.remove(level, pos, controllerPos);
            if (pos.equals(controllerPos)) continue;
            BlockState now = level.getBlockState(pos);
            if (!paint.fits(cell, now.getBlock(), storage)) continue;
            out.addAll(contents(level, pos));
            remove.add(pos);
        }
        // machines that drop what they hold as they go (a basin's input, say) are caught too: it all comes back. Things
        // lying in a Blueprint Designer's volume are not the building's, and stay where they are
        AABB box = p.bounds(t).inflate(1);
        AABB open = DesignerBlockEntity.openSpace(p);
        Set<UUID> before = new HashSet<>();
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) {
            if (open != null && open.contains(e.position())) {
                before.add(e.getUUID());
                continue;
            }
            // the Particle Accelerator's Cores in flight never despawn and cannot be picked up: they come back with the
            // building (a Converter cell lying loose is part of its machine and goes with it)
            if (Processors.isCore(e.getItem())) {
                out.add(e.getItem().copy());
                e.discard();
            } else if (Processors.cellBearing(e.getItem())) {
                e.discard();
            } else {
                before.add(e.getUUID());
            }
        }
        // a Blueprint Designer's floor and frame go without telling their neighbours, so nothing built in its volume (a
        // torch or carpet on the floor, a lever on a pillar) pops off as they go
        int flags = open != null ? Buildings.QUIET : Buildings.SET;
        for (BlockPos pos : remove) level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) {
            if (before.contains(e.getUUID()) || e.getItem().isEmpty()) continue;
            out.add(e.getItem().copy());
            e.discard();
        }
        return out;
    }

    /**
     * Everything a block holds: a vanilla container's slots, or what its item storage lets out, and what its tanks hold
     * ({@link #fluids}). Ports and the controller hold nothing.
     */
    public static List<ItemStack> contents(ServerLevel level, BlockPos pos) {
        List<ItemStack> out = new ArrayList<>();
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || be instanceof PortBlockEntity || be instanceof ControllerBlockEntity) return out;
        out.addAll(fluids(level, pos));
        // a Basin keeps its input and output in one inventory that its item storage only lets the output out of
        Container container = be instanceof Container c ? c
            : be instanceof BasinBlockEntity basin ? basin.itemCapability : null;
        if (container != null) {
            Transfers.takeAll(container, container.getContainerSize(), out);
            container.setChanged();
            return out;
        }
        Storage<ItemVariant> storage = ItemStorage.SIDED.find(level, pos, null);
        if (storage == null) return out;
        try (Transaction tx = Transaction.openOuter()) {
            for (StorageView<ItemVariant> view : storage.nonEmptyViews()) {
                ItemVariant variant = view.getResource();
                long n = view.extract(variant, view.getAmount(), tx);
                while (n > 0) {
                    int part = (int) Math.min(n, variant.toStack().getMaxStackSize());
                    out.add(variant.toStack(part));
                    n -= part;
                }
            }
            tx.commit();
        }
        return out;
    }

    /**
     * Empties the tanks of a block being dismantled into items: each fluid the Packager packages comes back as its
     * packaged fluid (one per 1 m3, as Satisfactory gives it back), any other fluid with a bucket as full buckets. Less
     * than one package or bucket is too little to give back and goes with the tank; a fluid with neither is left in it.
     */
    public static List<ItemStack> fluids(ServerLevel level, BlockPos pos) {
        List<ItemStack> out = new ArrayList<>();
        Storage<FluidVariant> tanks = FluidStorage.SIDED.find(level, pos, null);
        if (tanks == null) return out;
        try (Transaction tx = Transaction.openOuter()) {
            for (StorageView<FluidVariant> view : tanks.nonEmptyViews()) {
                var variant = view.getResource();
                if (variant.isBlank()) continue;
                ItemStack unit = ProductionLogic.packaged(variant.getFluid());
                long size;
                if (!unit.isEmpty()) {
                    size = (long) ProductionLogic.packagedMb(variant.getFluid()) * ProductionLogic.MB;
                } else if (variant.getFluid().getBucket() != Items.AIR) {
                    unit = new ItemStack(variant.getFluid().getBucket());
                    size = FluidConstants.BUCKET;
                } else {
                    continue;
                }
                if (size <= 0) continue;
                long units = view.getAmount() / size;
                if (units <= 0) continue;
                long took = view.extract(variant, view.getAmount(), tx);
                units = took / size;
                while (units > 0) {
                    int part = (int) Math.min(units, unit.getMaxStackSize());
                    out.add(unit.copyWithCount(part));
                    units -= part;
                }
            }
            tx.commit();
        }
        return out;
    }

    static List<ItemStack> copy(List<ItemStack> stacks) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : stacks) out.add(s.copy());
        return out;
    }

    /** The first block of the building that is not there any more (null if all are, or part of it is not loaded). */
    static @Nullable BlockPos missingMember(ServerLevel level, ControllerBlockEntity controller) {
        Placement p = controller.placement();
        if (p == null) return null;
        BuildingTemplate t = BuildingTemplate.of(p.def());
        if (t == null) return null;
        Paint paint = Paint.parse(controller.paint);
        boolean storage = p.def().logic() == Logic.STORAGE;
        BlockPos missing = null;
        for (BuildingTemplate.Cell cell : t.members) {
            BlockPos pos = p.at(cell);
            if (!level.isLoaded(pos)) return null;
            // a machine is put back by BuildingRepair instead
            if (missing == null && !BuildingRepair.machine(cell.state()) && !paint.fits(cell, level.getBlockState(pos).getBlock(), storage)) missing = pos;
        }
        return missing;
    }

    /**
     * A block of the building went without the Build Gun: the whole building comes down, and the refund and its
     * contents go into a crate for its company where the controller stood ({@link #giveBack}).
     */
    public static void collapse(ServerLevel level, ControllerBlockEntity controller, BlockPos missing) {
        BlockPos at = controller.getBlockPos().immutable();
        String company = controller.companyId;
        Siftec.LOGGER.info("SIFTEC: building {} at {} broke (its block at {} is gone); it comes down and gives back its refund",
            controller.buildingId, at.toShortString(), missing.toShortString());
        giveBack(level, at, company, Buildings.dismantle(level, controller));
    }

    record Gone(ServerLevel level, Placement placement, BlockPos controller, String company, List<ItemStack> kept) {
    }

    static final Deque<Gone> GONE = new ArrayDeque<>();

    /**
     * The controller itself was removed without the Build Gun: the rest comes down on the next tick. What the
     * controller held (its refund, input, output and buffer) is kept with it, and the factory simulation forgets it.
     */
    static void controllerGone(ServerLevel level, ControllerBlockEntity controller) {
        controller.removing = true;
        BlockPos at = controller.getBlockPos().immutable();
        Factories.removed(level, at);
        Placement p = controller.placement();
        List<ItemStack> kept = new ArrayList<>(copy(controller.refund));
        controller.refund.clear();
        kept.addAll(controller.takeContents());
        // the Converter's cell (its carrier) belongs to the machine and goes with it
        controller.carrier = ItemStack.EMPTY;
        if (p == null) {
            giveBack(level, at, controller.companyId, kept);
            return;
        }
        GONE.add(new Gone(level, p, at, controller.companyId, kept));
    }

    /**
     * What a building that came down by itself gives back: into a crate only its company's members open, so nobody
     * else picks it up (death.DeathCrates#stash); dropped where it stood when nobody owns it or no crate fits. A
     * Converter's cells, of any kind, are part of its machine and never given back.
     */
    static void giveBack(ServerLevel level, BlockPos at, String companyId, List<ItemStack> items) {
        List<ItemStack> out = new ArrayList<>(items);
        out.removeIf(s -> s.isEmpty() || Processors.cellBearing(s));
        if (out.isEmpty()) return;
        if (DeathCrates.stash(level, at, companyId, out)) return;
        Drops.all(level, at, 0.5, out);
    }

    /** Takes down the buildings that lost their controller (each server tick, from {@link Buildings#register}). */
    static void comeDown() {
        while (!GONE.isEmpty()) {
            Gone g = GONE.poll();
            Siftec.LOGGER.info("SIFTEC: building {} lost its controller at {}; it comes down and gives back its refund and contents", g.placement.def().id(), g.controller.toShortString());
            List<ItemStack> items = new ArrayList<>(g.kept);
            // a new building put down where this one's controller stood (in the same tick: a command, a selftest
            // laying out its row again) has the same blocks in the same places: they are the new one's, left alone
            Set<Long> keep = new HashSet<>();
            if (g.level.getBlockEntity(g.controller) instanceof ControllerBlockEntity now && !now.removing && now.placement() != null) {
                BuildingTemplate nt = BuildingTemplate.of(now.placement().def());
                if (nt != null) for (BuildingTemplate.Cell cell : nt.members) keep.add(now.placement().at(cell).asLong());
                Siftec.LOGGER.info("SIFTEC: building {} at {} was replaced before it came down; the new one's blocks stay", g.placement.def().id(), g.controller.toShortString());
            }
            items.addAll(takeDown(g.level, g.placement, g.controller, keep));
            giveBack(g.level, g.controller, g.company, items);
        }
    }
}
