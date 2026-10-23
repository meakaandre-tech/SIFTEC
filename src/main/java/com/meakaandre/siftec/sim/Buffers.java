package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildingPower;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.PortBlock;
import com.meakaandre.siftec.building.SimAccess;
import com.zurrtum.create.content.logistics.vault.ItemVaultBlockEntity;
import com.zurrtum.create.content.processing.basin.BasinBlockEntity;
import com.zurrtum.create.content.processing.basin.BasinInventory;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A node's buffers in the world, for the snapshot (when its chunk stops ticking) and the catch-up (when it ticks again):
 * the controller's input and output slots, a Basin's input and output slots, the Item Vaults of a Storage Container or
 * Truck Station, the inventory a belt ends in, and the tanks its fluid ports reach. The catch-up writes back only what
 * changed in the simulation since the snapshot, so anything that reached the building another way meanwhile stays.
 */
final class Buffers {
    enum Role {IN, OUT, BOTH}

    /** Slots from..to of a container, holding items of one role. */
    record Slots(Container c, int from, int to, Role role) {
    }

    record Items(Storage<ItemVariant> s, Role role) {
    }

    record Fluids(Storage<FluidVariant> s, Role role) {
    }

    final List<Slots> slots = new ArrayList<>();
    final List<Items> items = new ArrayList<>();
    final List<Fluids> fluids = new ArrayList<>();
    /** A Portable Miner drops its ore on the ground here. */
    @Nullable BlockPos ground;

    private Buffers() {
    }

    private static final long MB = FluidConstants.BUCKET / 1000;

    /**
     * Where the buffers are read from: the level, or (for a chunk that is leaving, whose block entities are still there
     * but which the level no longer hands out) that chunk for its own positions. Nothing here ever loads a chunk.
     */
    interface Reach {
        boolean has(BlockPos pos);

        @Nullable BlockEntity be(BlockPos pos);

        BlockState state(BlockPos pos);
    }

    static Reach level(ServerLevel level) {
        return new Reach() {
            @Override
            public boolean has(BlockPos pos) {
                return level.isLoaded(pos);
            }

            @Override
            public @Nullable BlockEntity be(BlockPos pos) {
                return level.isLoaded(pos) ? level.getBlockEntity(pos) : null;
            }

            @Override
            public BlockState state(BlockPos pos) {
                return level.getBlockState(pos);
            }
        };
    }

    /** The level, except that positions in this chunk are read from the chunk itself. */
    static Reach leaving(ServerLevel level, LevelChunk chunk) {
        ChunkPos at = chunk.getPos();
        return new Reach() {
            private boolean mine(BlockPos pos) {
                return pos.getX() >> 4 == at.x() && pos.getZ() >> 4 == at.z();
            }

            @Override
            public boolean has(BlockPos pos) {
                return mine(pos) || level.isLoaded(pos);
            }

            @Override
            public @Nullable BlockEntity be(BlockPos pos) {
                if (mine(pos)) return chunk.getBlockEntity(pos);
                return level.isLoaded(pos) ? level.getBlockEntity(pos) : null;
            }

            @Override
            public BlockState state(BlockPos pos) {
                return mine(pos) ? chunk.getBlockState(pos) : level.getBlockState(pos);
            }
        };
    }

    /** The buffers of a node, or null if its block entity is not there or a cell it reaches is in a chunk not loaded. */
    static @Nullable Buffers of(ServerLevel level, SimData.Node n) {
        return of(level, level(level), n);
    }

    static @Nullable Buffers of(ServerLevel level, Reach reach, SimData.Node n) {
        BlockPos pos = n.blockPos();
        if (!reach.has(pos)) return null;
        Buffers b = new Buffers();
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        BlockEntity be = reach.be(pos);
        if (n.kind.equals("inventory")) {
            Storage<ItemVariant> s = items(level, reach, pos, null);
            if (s == null) return null;
            b.items.add(new Items(s, Role.BOTH));
            return b;
        }
        if (!(be instanceof ControllerBlockEntity c)) return null;
        boolean store = n.mode.equals("store");
        List<SimAccess.Port> ports = SimAccess.ports(c);
        List<BlockPos> vaults = store ? SimAccess.cells(c, "create:item_vault") : List.of();
        List<BlockPos> tanks = store ? SimAccess.cells(c, "create:fluid_tank") : List.of();
        // every cell it reaches must be in a loaded chunk: reading or writing one that is not would load it
        for (SimAccess.Port p : ports) if (!reach.has(p.target())) return null;
        for (BlockPos cell : vaults) if (!reach.has(cell)) return null;
        for (BlockPos cell : tanks) if (!reach.has(cell)) return null;
        if (n.logic.equals("portable_miner")) b.ground = SimAccess.dropSpot(c);
        for (SimAccess.Port p : ports) {
            Role role = store ? Role.BOTH : p.kind().input() ? Role.IN : Role.OUT;
            if (p.kind().fluid()) {
                Storage<FluidVariant> s = fluids(level, reach, p.target());
                if (s != null && seen.put(s, true) == null) b.fluids.add(new Fluids(s, role));
                continue;
            }
            BlockEntity target = reach.be(p.target());
            if (target instanceof ControllerBlockEntity tc) {
                if (role == Role.IN) {
                    b.addSlots(seen, tc.input, 0, 1, Role.IN);
                    // only the Particle Accelerator and the Converter take their ingredients into the nine buffer slots
                    if (n.logic.equals("processor")) b.addSlots(seen, tc.buffer, 0, tc.buffer.getContainerSize(), Role.IN);
                } else {
                    b.addSlots(seen, tc.output, 0, 1, Role.OUT);
                    if (n.power.equals("nuclear")) b.addSlots(seen, tc.waste, 0, tc.waste.getContainerSize(), Role.OUT);
                }
            } else if (target instanceof BasinBlockEntity basin && !store) {
                Container inv = basin.itemCapability;
                if (role == Role.IN) b.addSlots(seen, inv, 0, Math.min(9, inv.getContainerSize()), Role.IN);
                else b.addSlots(seen, inv, 9, Math.min(18, inv.getContainerSize()), Role.OUT);
            } else if (target instanceof ItemVaultBlockEntity vault) {
                // each vault block's own slots: read straight from the block, never through its multiblock's controller
                Container inv = vault.getInventoryOfBlock();
                b.addSlots(seen, inv, 0, inv.getContainerSize(), role);
            } else {
                Storage<ItemVariant> s = items(level, reach, p.target(), null);
                if (s != null && seen.put(s, true) == null) b.items.add(new Items(s, role));
            }
        }
        // a generator's controller holds its fuel (and a nuclear plant's waste) even without a port reaching it
        if (n.mode.equals("gen") || n.logic.equals("miner")) {
            b.addSlots(seen, c.input, 0, 1, Role.IN);
            b.addSlots(seen, c.output, 0, 1, Role.OUT);
            // a nuclear plant's waste store, after its output slot (BuildingPower.WASTE_ROOM in all)
            if (n.power.equals("nuclear")) b.addSlots(seen, c.waste, 0, c.waste.getContainerSize(), Role.OUT);
        }
        for (BlockPos vault : vaults) {
            if (reach.be(vault) instanceof ItemVaultBlockEntity v) {
                Container inv = v.getInventoryOfBlock();
                b.addSlots(seen, inv, 0, inv.getContainerSize(), Role.BOTH);
            }
        }
        for (BlockPos tank : tanks) {
            Storage<FluidVariant> s = fluids(level, reach, tank);
            if (s != null && seen.put(s, true) == null) b.fluids.add(new Fluids(s, Role.BOTH));
        }
        return b;
    }

    private static @Nullable Storage<ItemVariant> items(ServerLevel level, Reach reach, BlockPos pos, @Nullable Direction side) {
        return ItemStorage.SIDED.find(level, pos, reach.state(pos), reach.be(pos), side);
    }

    private static @Nullable Storage<FluidVariant> fluids(ServerLevel level, Reach reach, BlockPos pos) {
        BlockState state = reach.state(pos);
        BlockEntity be = reach.be(pos);
        Storage<FluidVariant> s = FluidStorage.SIDED.find(level, pos, state, be, null);
        if (s != null || !level.isLoaded(pos)) return s;
        // what Create gives only as its own fluid inventory (the Item Drain), as the buildings read it
        return SimAccess.fluids(level, pos, null);
    }

    private void addSlots(Map<Object, Boolean> seen, Container c, int from, int to, Role role) {
        for (Slots s : slots) if (s.c == c && s.from == from) return;
        if (to <= from) return;
        slots.add(new Slots(c, from, to, role));
    }

    // ------------------------------------------------------------------------------------------------ reading

    /** Reads what the buffers hold into the node (input, output; fluids) and how much they hold at most. */
    void read(SimData.Node n) {
        n.in.clear();
        n.out.clear();
        n.fin.clear();
        n.fout.clear();
        long inCap = 0, outCap = 0, fInCap = 0, fOutCap = 0;
        for (Slots s : slots) {
            Map<String, Long> into = s.role == Role.IN ? n.in : n.out;
            for (int i = s.from; i < s.to; i++) {
                ItemStack stack = s.c.getItem(i);
                if (!stack.isEmpty()) Sim.add(into, Keys.of(stack), stack.getCount());
                long cap = stack.isEmpty() ? 64 : stack.getMaxStackSize();
                if (s.role == Role.IN) inCap += cap;
                else outCap += cap;
            }
        }
        for (Items s : items) {
            Map<String, Long> into = s.role == Role.IN ? n.in : n.out;
            long cap = 0;
            for (StorageView<ItemVariant> v : s.s) {
                cap += v.getCapacity();
                if (!v.isResourceBlank() && v.getAmount() > 0) Sim.add(into, Keys.of(v.getResource()), v.getAmount());
            }
            if (s.role == Role.IN) inCap += cap;
            else outCap += cap;
        }
        for (Fluids s : fluids) {
            Map<String, Long> into = s.role == Role.IN ? n.fin : n.fout;
            long cap = 0;
            for (StorageView<FluidVariant> v : s.s) {
                cap += v.getCapacity() / MB;
                if (!v.isResourceBlank() && v.getAmount() > 0) Sim.add(into, Keys.fluid(v.getResource()), v.getAmount() / MB);
            }
            if (s.role == Role.IN) fInCap += cap;
            else fOutCap += cap;
        }
        if (ground != null) outCap = 64;
        // a nuclear plant's output holds its waste up to the room the real plant gives it, not all its slots could
        if (n.power.equals("nuclear")) outCap = Math.min(outCap, BuildingPower.WASTE_ROOM);
        // the model's own figures stand where the buffers say less (a storage's capacity is what it is)
        if (!slots.isEmpty() || !items.isEmpty()) {
            if (n.mode.equals("store")) n.outCap = outCap;
            else {
                if (inCap > 0) n.inCap = inCap;
                if (outCap > 0) n.outCap = outCap;
            }
        }
        if (!fluids.isEmpty()) {
            if (n.mode.equals("store")) n.fluidOutCap = fOutCap;
            else {
                if (fInCap > 0) n.fluidInCap = fInCap;
                if (fOutCap > 0) n.fluidOutCap = fOutCap;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ writing back

    /** Writes back what changed since the snapshot: removals first, then additions. What fits nowhere is dropped at {@code at}. */
    void write(ServerLevel level, SimData.Node n, BlockPos at) {
        Map<String, Long> dIn = delta(n.in, n.baseIn), dOut = delta(n.out, n.baseOut);
        Map<String, Long> dFin = delta(n.fin, n.baseFin), dFout = delta(n.fout, n.baseFout);
        boolean store = n.mode.equals("store");
        for (Map.Entry<String, Long> e : dIn.entrySet()) if (e.getValue() < 0) remove(e.getKey(), -e.getValue(), store ? Role.BOTH : Role.IN);
        for (Map.Entry<String, Long> e : dOut.entrySet()) if (e.getValue() < 0) remove(e.getKey(), -e.getValue(), store ? Role.BOTH : Role.OUT);
        for (Map.Entry<String, Long> e : dFin.entrySet()) if (e.getValue() < 0) removeFluid(e.getKey(), -e.getValue(), store ? Role.BOTH : Role.IN);
        for (Map.Entry<String, Long> e : dFout.entrySet()) if (e.getValue() < 0) removeFluid(e.getKey(), -e.getValue(), store ? Role.BOTH : Role.OUT);
        List<ItemStack> spill = new ArrayList<>();
        for (Map.Entry<String, Long> e : dIn.entrySet()) if (e.getValue() > 0) spill.addAll(add(e.getKey(), e.getValue(), store ? Role.BOTH : Role.IN));
        for (Map.Entry<String, Long> e : dOut.entrySet()) if (e.getValue() > 0) {
            if (ground != null) {
                spill.addAll(stacks(e.getKey(), e.getValue()));
                continue;
            }
            spill.addAll(add(e.getKey(), e.getValue(), store ? Role.BOTH : Role.OUT));
        }
        long lostFluid = 0;
        for (Map.Entry<String, Long> e : dFin.entrySet()) if (e.getValue() > 0) lostFluid += addFluid(e.getKey(), e.getValue(), store ? Role.BOTH : Role.IN);
        for (Map.Entry<String, Long> e : dFout.entrySet()) if (e.getValue() > 0) lostFluid += addFluid(e.getKey(), e.getValue(), store ? Role.BOTH : Role.OUT);
        BlockPos drop = ground != null ? ground : at;
        for (ItemStack s : spill) {
            if (s.isEmpty()) continue;
            if (ground != null) {
                ItemEntity entity = new ItemEntity(level, drop.getX() + 0.5, drop.getY() + 0.1, drop.getZ() + 0.5, s);
                entity.setDeltaMovement(Vec3.ZERO);
                level.addFreshEntity(entity);
            } else {
                Containers.dropItemStack(level, drop.getX() + 0.5, drop.getY() + 1.1, drop.getZ() + 0.5, s);
            }
        }
        if (ground == null && !spill.isEmpty()) Siftec.LOGGER.info("SIFTEC: {} at {}: {} stacks the catch-up had no room for were dropped", n.building, at.toShortString(), spill.size());
        if (lostFluid > 0) Siftec.LOGGER.info("SIFTEC: {} at {}: {} mB the catch-up had no room for", n.building, at.toShortString(), lostFluid);
        for (Slots s : slots) s.c.setChanged();
    }

    static Map<String, Long> delta(Map<String, Long> now, Map<String, Long> base) {
        Map<String, Long> d = new HashMap<>();
        for (Map.Entry<String, Long> e : now.entrySet()) Sim.add(d, e.getKey(), e.getValue());
        for (Map.Entry<String, Long> e : base.entrySet()) Sim.add(d, e.getKey(), -e.getValue());
        return d;
    }

    private static boolean matches(Role want, Role have) {
        return want == Role.BOTH || have == Role.BOTH || want == have;
    }

    private static List<ItemStack> stacks(String key, long count) {
        List<ItemStack> out = new ArrayList<>();
        int max = Math.max(1, Keys.max(key));
        while (count > 0) {
            int n = (int) Math.min(count, max);
            ItemStack s = Keys.stack(key, n);
            if (s.isEmpty()) break;
            out.add(s);
            count -= n;
        }
        return out;
    }

    private void remove(String key, long count, Role role) {
        ItemVariant v = Keys.variant(key);
        if (v.isBlank()) return;
        for (Slots s : slots) {
            if (count <= 0) return;
            if (!matches(role, s.role)) continue;
            for (int i = s.from; i < s.to && count > 0; i++) {
                ItemStack stack = s.c.getItem(i);
                if (stack.isEmpty() || !v.matches(stack)) continue;
                int take = (int) Math.min(count, stack.getCount());
                stack.shrink(take);
                if (stack.isEmpty()) s.c.setItem(i, ItemStack.EMPTY);
                count -= take;
            }
        }
        for (Items s : items) {
            if (count <= 0) return;
            if (!matches(role, s.role)) continue;
            try (Transaction tx = Transaction.openOuter()) {
                count -= s.s.extract(v, count, tx);
                tx.commit();
            }
        }
    }

    private List<ItemStack> add(String key, long count, Role role) {
        ItemVariant v = Keys.variant(key);
        if (v.isBlank()) return List.of();
        for (Items s : items) {
            if (count <= 0) break;
            if (!matches(role, s.role)) continue;
            try (Transaction tx = Transaction.openOuter()) {
                count -= s.s.insert(v, count, tx);
                tx.commit();
            }
        }
        int max = Math.max(1, Keys.max(key));
        for (Slots s : slots) {
            if (count <= 0) break;
            if (!matches(role, s.role)) continue;
            for (int i = s.from; i < s.to && count > 0; i++) {
                ItemStack stack = s.c.getItem(i);
                if (stack.isEmpty() || !v.matches(stack) || stack.getCount() >= max) continue;
                int put = (int) Math.min(count, max - stack.getCount());
                stack.grow(put);
                count -= put;
            }
            for (int i = s.from; i < s.to && count > 0; i++) {
                if (!s.c.getItem(i).isEmpty()) continue;
                int put = (int) Math.min(count, max);
                setItem(s.c, i, v.toStack(put));
                count -= put;
            }
        }
        return count > 0 ? stacks(key, count) : List.of();
    }

    /** Sets a slot even where the container would check what goes in (a Basin's own check is for players and funnels). */
    private static void setItem(Container c, int slot, ItemStack stack) {
        if (c instanceof BasinInventory basin) {
            basin.disableCheck();
            basin.setItem(slot, stack);
            basin.enableCheck();
        } else {
            c.setItem(slot, stack);
        }
    }

    private void removeFluid(String key, long mb, Role role) {
        var fluid = Keys.fluidOf(key);
        for (Fluids s : fluids) {
            if (mb <= 0) return;
            if (!matches(role, s.role)) continue;
            try (Transaction tx = Transaction.openOuter()) {
                mb -= s.s.extract(FluidVariant.of(fluid), mb * MB, tx) / MB;
                tx.commit();
            }
        }
    }

    /** Puts fluid back; returns the mB that fitted nowhere. */
    private long addFluid(String key, long mb, Role role) {
        var fluid = Keys.fluidOf(key);
        for (Fluids s : fluids) {
            if (mb <= 0) return 0;
            if (!matches(role, s.role)) continue;
            try (Transaction tx = Transaction.openOuter()) {
                mb -= s.s.insert(FluidVariant.of(fluid), mb * MB, tx) / MB;
                tx.commit();
            }
        }
        return Math.max(0, mb);
    }

    /** Puts what a belt or pipe brought into a building whose chunk ticks: at its input. Returns how many went in. */
    static long insert(ServerLevel level, SimData.Node n, String key, long count, boolean fluid) {
        Buffers b = of(level, n);
        if (b == null) return 0;
        Role role = n.mode.equals("store") ? Role.BOTH : Role.IN;
        if (fluid) {
            long left = b.addFluid(key, count, role);
            return count - left;
        }
        long before = count;
        long left = 0;
        for (ItemStack s : b.add(key, count, role)) left += s.getCount();
        for (Slots s : b.slots) s.c.setChanged();
        return before - left;
    }

    /**
     * Puts things back where a building keeps what it made (its output, or a storage's vaults): what came off a belt or
     * pipe that is no longer there. What fits nowhere is dropped at the building. Returns how many were handled.
     */
    static long putBack(ServerLevel level, SimData.Node n, String key, long count, boolean fluid) {
        Buffers b = of(level, n);
        if (b == null) return 0;
        Role role = n.mode.equals("store") ? Role.BOTH : Role.OUT;
        if (fluid) {
            long lost = b.addFluid(key, count, role);
            if (lost > 0) Siftec.LOGGER.info("SIFTEC: {} at {}: {} mB had no room to go back to", n.building, n.blockPos().toShortString(), lost);
            return count;
        }
        BlockPos at = n.blockPos();
        for (ItemStack s : b.add(key, count, role)) Containers.dropItemStack(level, at.getX() + 0.5, at.getY() + 1.1, at.getZ() + 0.5, s);
        for (Slots s : b.slots) s.c.setChanged();
        return count;
    }

    /** Takes what a building whose chunk ticks has made, for a belt or pipe to a simulated one. Returns how many came. */
    static Map<String, Long> extract(ServerLevel level, SimData.Node n, long most, boolean fluid) {
        Map<String, Long> got = new HashMap<>();
        Buffers b = of(level, n);
        if (b == null || most <= 0) return got;
        Role role = n.mode.equals("store") ? Role.BOTH : Role.OUT;
        if (fluid) {
            for (Fluids s : b.fluids) {
                if (most <= 0 || !matches(role, s.role)) continue;
                List<FluidVariant> have = new ArrayList<>();
                for (StorageView<FluidVariant> v : s.s) if (!v.isResourceBlank() && v.getAmount() >= MB && !have.contains(v.getResource())) have.add(v.getResource());
                for (FluidVariant fv : have) {
                    if (most <= 0) break;
                    try (Transaction tx = Transaction.openOuter()) {
                        long took = s.s.extract(fv, most * MB, tx) / MB;
                        tx.commit();
                        most -= took;
                        Sim.add(got, Keys.fluid(fv), took);
                    }
                }
            }
            return got;
        }
        for (Slots s : b.slots) {
            if (!matches(role, s.role)) continue;
            for (int i = s.from; i < s.to && most > 0; i++) {
                ItemStack stack = s.c.getItem(i);
                if (stack.isEmpty()) continue;
                int take = (int) Math.min(most, stack.getCount());
                Sim.add(got, Keys.of(stack), take);
                stack.shrink(take);
                if (stack.isEmpty()) s.c.setItem(i, ItemStack.EMPTY);
                most -= take;
            }
            s.c.setChanged();
        }
        for (Items s : b.items) {
            if (!matches(role, s.role)) continue;
            List<ItemVariant> have = new ArrayList<>();
            for (StorageView<ItemVariant> v : s.s) if (!v.isResourceBlank() && v.getAmount() > 0 && !have.contains(v.getResource())) have.add(v.getResource());
            for (ItemVariant iv : have) {
                if (most <= 0) break;
                try (Transaction tx = Transaction.openOuter()) {
                    long took = s.s.extract(iv, most, tx);
                    tx.commit();
                    most -= took;
                    Sim.add(got, Keys.of(iv), took);
                }
            }
        }
        return got;
    }
}
