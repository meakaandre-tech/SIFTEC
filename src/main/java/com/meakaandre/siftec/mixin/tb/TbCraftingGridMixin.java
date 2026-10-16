package com.meakaandre.siftec.mixin.tb;

import com.meakaandre.siftec.compat.TbGridSlot;
import com.meakaandre.siftec.compat.TravelersBackpackCompat;
import com.tiviacz.travelersbackpack.inventory.BackpackWrapper;
import com.tiviacz.travelersbackpack.inventory.menu.BackpackBaseMenu;
import com.tiviacz.travelersbackpack.inventory.upgrades.crafting.CraftingUpgrade;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * The backpack's built-in crafting grid is a Crafting Upgrade that only uses its top-left 2x2: the right column and
 * the bottom row of its 3x3 are switched off (nothing goes in, nothing is shown), so only 2x2 recipes fit, as in the
 * vanilla inventory. A Crafting Upgrade proper (see {@link TravelersBackpackCompat#installCraftingUpgrade}) has all
 * nine.
 */
@Pseudo
@Mixin(targets = "com.tiviacz.travelersbackpack.inventory.upgrades.crafting.CraftingUpgrade", remap = false)
public abstract class TbCraftingGridMixin {
    @Inject(method = "getUpgradeSlots(Lcom/tiviacz/travelersbackpack/inventory/menu/BackpackBaseMenu;Lcom/tiviacz/travelersbackpack/inventory/BackpackWrapper;II)Ljava/util/List;",
        at = @At("RETURN"))
    private void siftec$twoByTwo(BackpackBaseMenu menu, BackpackWrapper wrapper, int x, int y, CallbackInfoReturnable<List<Slot>> cir) {
        CraftingUpgrade self = (CraftingUpgrade) (Object) this;
        if (!TravelersBackpackCompat.isGrid(self.getDataHolderStack())) return;
        List<Slot> slots = cir.getReturnValue();
        for (int i = 0; i < slots.size() && i < 9; i++) {
            int row = i / 3, column = i % 3;
            if (row == 2 || column == 2) slots.set(i, new TbGridSlot(slots.get(i)));
        }
    }
}
