package com.meakaandre.siftec.drone;

import com.zurrtum.create.content.logistics.box.PackageItem;
import net.minecraft.core.Direction;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * A Drone Port's slots: nine packages waiting to leave, one stack of fire charges, nine packages that have
 * arrived. Belts, funnels and hoppers put packages and fire charges in from the top and sides, and take
 * arrivals out from below.
 */
public class PortContainer extends SimpleContainer implements WorldlyContainer {
    public static final int OUT = 0, FUEL = 9, IN = 10, SIZE = 19;
    private static final int[] FEED = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, TAKE = {10, 11, 12, 13, 14, 15, 16, 17, 18};
    private final Runnable changed;

    public PortContainer(Runnable changed) {
        super(SIZE);
        this.changed = changed;
    }

    @Override
    public void setChanged() {
        super.setChanged();
        changed.run();
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return side == Direction.DOWN ? TAKE : FEED;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot < FUEL ? PackageItem.isPackage(stack) : slot == FUEL && stack.is(Items.FIRE_CHARGE);
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
        return canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return slot >= IN;
    }

    /** Adds what fits to a run of slots and returns what did not. */
    public ItemStack add(ItemStack stack, int from, int to) {
        ItemStack left = stack.copy();
        for (int i = from; i < to && !left.isEmpty(); i++) {
            ItemStack here = getItem(i);
            if (here.isEmpty()) {
                setItem(i, left.copyAndClear());
            } else if (ItemStack.isSameItemSameComponents(here, left)) {
                int moved = Math.min(left.getCount(), here.getMaxStackSize() - here.getCount());
                here.grow(moved);
                left.shrink(moved);
            }
        }
        setChanged();
        return left;
    }

    public boolean empty(int from, int to) {
        for (int i = from; i < to; i++) if (!getItem(i).isEmpty()) return false;
        return true;
    }
}
