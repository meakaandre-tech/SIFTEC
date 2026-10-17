package com.meakaandre.siftec.backpack;

import com.meakaandre.siftec.hub.Milestone;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * What a player carries, seen as one container: the containers of {@link Milestone.Cost#containers} (the inventory,
 * then the worn backpack) one after the other, so whatever pays from a container (the Build Gun, vehicles, blueprints,
 * jetpack fuel, filters) pays from the backpack too. Slot numbers run through the inventory and on into the backpack.
 * Items are taken out with {@link #removeItem}, which a backpack slot needs to save what is left in it.
 */
public final class Carried implements Container {
    private final List<Container> parts;

    public Carried(List<Container> parts) {
        this.parts = parts;
    }

    /** What the player carries. On the client only the inventory is known. */
    public static Carried of(Player player) {
        return new Carried(Milestone.Cost.containers(player.getInventory()));
    }

    @Override
    public int getContainerSize() {
        int n = 0;
        for (Container part : parts) n += part.getContainerSize();
        return n;
    }

    @Override
    public boolean isEmpty() {
        for (Container part : parts) if (!part.isEmpty()) return false;
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        for (Container part : parts) {
            if (slot < part.getContainerSize()) return part.getItem(slot);
            slot -= part.getContainerSize();
        }
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        for (Container part : parts) {
            if (slot < part.getContainerSize()) return part.removeItem(slot, amount);
            slot -= part.getContainerSize();
        }
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        for (Container part : parts) {
            if (slot < part.getContainerSize()) return part.removeItemNoUpdate(slot);
            slot -= part.getContainerSize();
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        for (Container part : parts) {
            if (slot < part.getContainerSize()) {
                part.setItem(slot, stack);
                return;
            }
            slot -= part.getContainerSize();
        }
    }

    @Override
    public void setChanged() {
        for (Container part : parts) part.setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        for (Container part : parts) part.clearContent();
    }
}
