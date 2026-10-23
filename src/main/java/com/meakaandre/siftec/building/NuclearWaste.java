package com.meakaandre.siftec.building;

import com.meakaandre.siftec.util.Drops;
import com.meakaandre.siftec.util.Transfers;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/** The waste of nuclear fuel rods: what a plant's output holds, whether a rod's waste fits, and where a reactor's goes. */
public final class NuclearWaste {
    private NuclearWaste() {
    }

    /** The waste a fuel rod leaves (its id and how many), or null if it leaves none. */
    public static String @Nullable [] wasteOf(ItemStack rod) {
        BuildingPower.readFuel();
        String id = BuiltInRegistries.ITEM.getKey(rod.getItem()).toString();
        String waste = BuildingPower.WASTE.get(id);
        return waste == null ? null : new String[]{waste, String.valueOf(BuildingPower.WASTE_COUNT.getOrDefault(id, 1))};
    }

    /** The waste a plant's output holds now, or -1 if something else is in it. */
    public static int wasteHeld(ControllerBlockEntity c, Item wasteItem) {
        int held = 0;
        ItemStack out = c.output.getItem(0);
        if (!out.isEmpty()) {
            if (!out.is(wasteItem)) return -1;
            held += out.getCount();
        }
        for (int i = 0; i < c.waste.getContainerSize(); i++) {
            ItemStack s = c.waste.getItem(i);
            if (s.isEmpty()) continue;
            if (!s.is(wasteItem)) return -1;
            held += s.getCount();
        }
        return held;
    }

    /** All the waste a plant's output holds (its output slot and its waste store), whatever it is. */
    public static int wasteHeld(ControllerBlockEntity c) {
        int held = c.output.getItem(0).getCount();
        for (int i = 0; i < c.waste.getContainerSize(); i++) held += c.waste.getItem(i).getCount();
        return held;
    }

    /** True if the waste a fuel rod leaves fits into the plant's output now. */
    static boolean wasteFits(ControllerBlockEntity c, ItemStack rod) {
        return wasteFits(c, rod, 0);
    }

    /**
     * True if the waste a fuel rod leaves fits into the plant's output, after {@code promised} waste still to come from
     * rods already handed to its fuel acceptors (they leave it as New Age uses them up).
     */
    static boolean wasteFits(ControllerBlockEntity c, ItemStack rod, int promised) {
        BuildingPower.readFuel();
        String id = BuiltInRegistries.ITEM.getKey(rod.getItem()).toString();
        String waste = BuildingPower.WASTE.get(id);
        if (waste == null) return true;
        Item wasteItem = BuiltInRegistries.ITEM.getValue(Identifier.parse(waste));
        int n = BuildingPower.WASTE_COUNT.getOrDefault(id, 1);
        int held = wasteHeld(c, wasteItem);
        if (held < 0) return false;
        // the output slot and the waste store hold at most WASTE_ROOM, and never more than their stacks can
        int room = Math.min(BuildingPower.WASTE_ROOM, wasteItem.getDefaultMaxStackSize() * (1 + c.waste.getContainerSize()));
        return held + promised + n <= room;
    }

    /** The waste these rods will leave (a stack of rods in a fuel acceptor, not yet used up). */
    static int pendingWaste(ItemStack rods) {
        if (rods.isEmpty()) return 0;
        BuildingPower.readFuel();
        String id = BuiltInRegistries.ITEM.getKey(rods.getItem()).toString();
        return BuildingPower.WASTE.containsKey(id) ? BuildingPower.WASTE_COUNT.getOrDefault(id, 1) * rods.getCount() : 0;
    }

    /** Satisfactory's waste for fuel rods a player-built New Age reactor took: next to its fuel acceptor, or on top. */
    public static void reactorWaste(Level level, BlockPos acceptor, ItemStack fuel, int used) {
        BuildingPower.readFuel();
        String id = BuiltInRegistries.ITEM.getKey(fuel.getItem()).toString();
        String waste = BuildingPower.WASTE.get(id);
        if (waste == null) return;
        ItemStack out = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(waste)), BuildingPower.WASTE_COUNT.getOrDefault(id, 1) * used);
        // the Nuclear Power Plant's own reactor: its waste goes to the plant's output, which the plant made room for
        BlockPos plant = BuildingIndex.controllerOf(level, acceptor);
        if (plant != null && level.getBlockEntity(plant) instanceof ControllerBlockEntity c) {
            ItemStack slot = c.output.getItem(0);
            c.wasteMade += out.getCount();
            if (slot.isEmpty()) {
                c.output.setItem(0, out.split(Math.min(out.getCount(), out.getMaxStackSize())));
            } else if (ItemStack.isSameItemSameComponents(slot, out)) {
                // never past a full stack (the plant takes no rod whose waste would not fit, counting what its acceptors hold)
                int move = Math.min(out.getCount(), slot.getMaxStackSize() - slot.getCount());
                if (move > 0) {
                    slot.grow(move);
                    out.shrink(move);
                }
            }
            // the rest into the waste store (room for WASTE_ROOM in all, which wasteFits keeps to)
            if (!out.isEmpty()) out = c.waste.addItem(out);
            if (!out.isEmpty()) Drops.at(level, plant, 1.1, out.copy());
            c.output.setChanged();
            return;
        }
        for (Direction side : Direction.values()) {
            if (out.isEmpty()) break;
            var into = ItemStorage.SIDED.find(level, acceptor.relative(side), side.getOpposite());
            if (into == null) continue;
            out.shrink((int) Transfers.insert(into, ItemVariant.of(out), out.getCount()));
        }
        if (!out.isEmpty()) Drops.at(level, acceptor, 1.1, out);
    }
}
