package com.meakaandre.siftec.compat;

import com.tiviacz.travelersbackpack.inventory.menu.slot.CraftingSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A slot of the backpack's built-in crafting grid outside its 2x2 (mixin/tb/TbCraftingGridMixin): switched off, so
 * nothing goes in and it is not drawn. Still a crafting slot, so the mod's own handling of the grid keeps working.
 */
public final class TbGridSlot extends CraftingSlot {
    public TbGridSlot(Slot from) {
        super(from.container, from.getContainerSlot(), from.x, from.y);
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return false;
    }

    @Override
    public boolean mayPickup(Player player) {
        return false;
    }

    @Override
    public boolean isActive() {
        return false;
    }
}
