package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.backpack.BackpackRows;
import com.meakaandre.siftec.backpack.BackpackSlot;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Every window remembers where its backpack rows are (see {@link BackpackRows}). Moves into the player's inventory
 * spill into the backpack after the main rows, and shift-click on a backpack slot acts like one on a main slot.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class MenuBackpackMixin implements BackpackRows.Holder {
    @Shadow
    @Final
    public NonNullList<Slot> slots;

    @Unique
    private BackpackRows.Layout siftec$layout;

    @Override
    public BackpackRows.Layout siftec$backpack() {
        return siftec$layout;
    }

    @Override
    public void siftec$setBackpack(BackpackRows.Layout layout) {
        siftec$layout = layout;
    }

    @Inject(method = "moveItemStackTo", at = @At("HEAD"), cancellable = true)
    private void siftec$intoBackpack(ItemStack stack, int start, int end, boolean reverse, CallbackInfoReturnable<Boolean> cir) {
        if (siftec$layout == null) return;
        Boolean moved = BackpackRows.moveTo((AbstractContainerMenu) (Object) this, stack, start, end, reverse);
        if (moved != null) cir.setReturnValue(moved);
    }

    @Inject(method = "doClick", at = @At("HEAD"), cancellable = true)
    private void siftec$quickMoveBackpack(int slotId, int button, ContainerInput input, Player player, CallbackInfo ci) {
        if (siftec$layout == null || input != ContainerInput.QUICK_MOVE || !siftec$layout.isBackpack(slotId) || slotId >= slots.size()) return;
        Slot slot = slots.get(slotId);
        if (!(slot instanceof BackpackSlot)) return;
        ci.cancel();
        if (!slot.hasItem() || !slot.mayPickup(player)) return;
        BackpackRows.quickMove((AbstractContainerMenu) (Object) this, siftec$layout, slot, player);
    }

    /**
     * Should a window ever end up with the rows on one side only, the client drops the extra items it is sent
     * instead of failing on a slot it does not have.
     */
    @ModifyVariable(method = "initializeContents", at = @At("HEAD"), argsOnly = true)
    private List<ItemStack> siftec$onlyKnownSlots(List<ItemStack> items) {
        return items.size() > slots.size() ? items.subList(0, slots.size()) : items;
    }
}
