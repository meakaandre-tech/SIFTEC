package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.backpack.BackpackRows;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The player's own inventory window gets the backpack rows as soon as it is made, on both sides. */
@Mixin(InventoryMenu.class)
public abstract class InventoryMenuMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void siftec$backpackSlots(Inventory inventory, boolean active, Player owner, CallbackInfo ci) {
        BackpackRows.attach((InventoryMenu) (Object) this, owner);
    }
}
