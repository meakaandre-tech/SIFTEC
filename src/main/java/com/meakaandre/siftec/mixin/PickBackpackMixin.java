package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.backpack.Backpack;
import net.minecraft.core.NonNullList;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pick block (middle click) finds an item in the backpack when the inventory has none: it swaps into a hotbar slot
 * (the one vanilla would use) and that slot is selected, like an item picked from the main rows.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PickBackpackMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "tryPickItem", at = @At("HEAD"), cancellable = true)
    private void siftec$pickFromBackpack(ItemStack stack, CallbackInfo ci) {
        if (stack.isEmpty() || player.hasInfiniteMaterials() || !stack.isItemEnabled(player.level().enabledFeatures())) return;
        Inventory inventory = player.getInventory();
        if (inventory.findSlotMatchingItem(stack) != -1) return;
        NonNullList<ItemStack> items = player.getAttachedOrCreate(Backpack.CONTENTS).items;
        int open = Backpack.unlocked(player);
        for (int i = 0; i < items.size(); i++) {
            ItemStack found = items.get(i);
            if (found.isEmpty() || !ItemStack.isSameItemSameComponents(found, stack)) continue;
            int hotbar = inventory.getSuitableHotbarSlot();
            ItemStack held = inventory.getItem(hotbar);
            // what was in the hotbar slot takes the backpack slot's place; a closed slot only gives, so it then has to be empty
            if (!held.isEmpty() && i >= open) continue;
            inventory.setItem(hotbar, found);
            items.set(i, held);
            inventory.setSelectedSlot(hotbar);
            player.connection.send(new ClientboundSetHeldSlotPacket(inventory.getSelectedSlot()));
            player.inventoryMenu.broadcastChanges();
            ci.cancel();
            return;
        }
    }
}
