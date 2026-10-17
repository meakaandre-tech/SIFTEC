package com.meakaandre.siftec.mixin.tb;

import com.meakaandre.siftec.compat.TravelersBackpackCompat;
import com.tiviacz.travelersbackpack.inventory.menu.BackpackBaseMenu;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The backpack's crafting result slot (ResultSlotExt) overrides mayPickup to always say yes, and the Crafting Upgrade
 * puts in its own subclass of it (CraftingUpgrade$2) that overrides it once more ("is the tab open"), so
 * mixin/SlotMixin's lock reaches neither: here a locked result can be seen but not taken out, as in a crafting table.
 * Click, number key and drop all ask mayPickup; shift-craft does not and is checked in {@link TbMenuMixin}.
 */
@Pseudo
@Mixin(targets = {"com.tiviacz.travelersbackpack.inventory.menu.slot.ResultSlotExt",
    "com.tiviacz.travelersbackpack.inventory.upgrades.crafting.CraftingUpgrade$2"}, remap = false)
public abstract class TbResultSlotMixin {
    @Inject(method = "mayPickup(Lnet/minecraft/world/entity/player/Player;)Z", at = @At("HEAD"), cancellable = true)
    private void siftec$locked(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (player.containerMenu instanceof BackpackBaseMenu menu && !TravelersBackpackCompat.mayTakeResult(player, menu, ((Slot) (Object) this).getItem())) {
            cir.setReturnValue(false);
        }
    }
}
