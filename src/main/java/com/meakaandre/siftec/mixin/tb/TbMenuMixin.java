package com.meakaandre.siftec.mixin.tb;

import com.meakaandre.siftec.compat.TbEquipSlots;
import com.tiviacz.travelersbackpack.inventory.menu.BackpackBaseMenu;
import com.tiviacz.travelersbackpack.util.Reference;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The window of the player's own worn backpack is their inventory, so it carries the armour and offhand slots too
 * ({@link TbEquipSlots}). They are added after the player's inventory slots and counted with the slots the mod keeps
 * when it rebuilds the upgrade slots. Shift-clicking moves armour between them and the rest of the window.
 */
@Pseudo
@Mixin(targets = "com.tiviacz.travelersbackpack.inventory.menu.BackpackBaseMenu", remap = false)
public abstract class TbMenuMixin {
    @Shadow
    public int unmodifiableSlotCount;

    @Shadow
    protected abstract Slot addSlot(Slot slot);

    @Shadow
    protected abstract boolean moveItemStackTo(ItemStack stack, int from, int to, boolean reverse);

    @Inject(method = "addSlots()V", at = @At(value = "INVOKE",
        target = "Lcom/tiviacz/travelersbackpack/inventory/menu/BackpackBaseMenu;addUpgradeListeners()V"))
    private void siftec$equipSlots(CallbackInfo ci) {
        BackpackBaseMenu self = (BackpackBaseMenu) (Object) this;
        if (self.getWrapper() == null || self.getWrapper().getScreenID() != Reference.WEARABLE_SCREEN_ID) return;
        for (Slot slot : TbEquipSlots.create(self.getPlayerInventory())) addSlot(slot);
        unmodifiableSlotCount = self.slots.size();
    }

    @Inject(method = "quickMoveStack(Lnet/minecraft/world/entity/player/Player;I)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"), cancellable = true)
    private void siftec$equipQuickMove(Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
        BackpackBaseMenu self = (BackpackBaseMenu) (Object) this;
        if (index < 0 || index >= self.slots.size()) return;
        Slot slot = self.slots.get(index);
        if (!slot.hasItem()) return;
        ItemStack stack = slot.getItem();
        ItemStack before = stack.copy();
        if (TbEquipSlots.isEquipSlot(slot)) {
            // armour and offhand go into the backpack first, then the inventory
            if (!moveItemStackTo(stack, self.BACKPACK_INV_START, self.BACKPACK_INV_END, false)
                && !moveItemStackTo(stack, self.PLAYER_INV_START, self.PLAYER_HOT_END, true)) {
                cir.setReturnValue(ItemStack.EMPTY);
                return;
            }
        } else if ((index >= self.PLAYER_INV_START && index < self.PLAYER_HOT_END) || (index >= self.BACKPACK_INV_START && index < self.BACKPACK_INV_END)) {
            // a piece of armour from the inventory or the backpack goes on, when its slot is free
            EquipmentSlot equipment = player.getEquipmentSlotForItem(stack);
            if (equipment.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) return;
            int target = TbEquipSlots.find(self, equipment);
            if (target < 0 || self.slots.get(target).hasItem() || !self.slots.get(target).mayPlace(stack)) return;
            if (!moveItemStackTo(stack, target, target + 1, false)) return;
        } else {
            return;
        }
        // the leftover is set back, not just marked changed: a backpack slot saves only what is set into it
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY, before);
        else slot.set(stack);
        cir.setReturnValue(stack.getCount() == before.getCount() ? ItemStack.EMPTY : before);
    }
}
