package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.backpack.Backpack;
import com.meakaandre.siftec.backpack.BackpackContainer;
import com.meakaandre.siftec.backpack.BackpackSlot;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the backpack slots to the player's own inventory window, after all of vanilla's slots. */
@Mixin(InventoryMenu.class)
public abstract class InventoryMenuMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void siftec$backpackSlots(Inventory inventory, boolean active, Player owner, CallbackInfo ci) {
        // the server keeps the real items; the client just mirrors what it is sent
        Container container = active ? new BackpackContainer(owner) : new SimpleContainer(Backpack.SIZE);
        for (int i = 0; i < Backpack.SIZE; i++) {
            ((MenuInvoker) this).siftec$addSlot(new BackpackSlot(owner, container, i));
        }
    }
}
