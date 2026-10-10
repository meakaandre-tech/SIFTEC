package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.backpack.Backpack;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Curse of Vanishing takes items out of the backpack on death, as it does from the inventory. */
@Mixin(Player.class)
public abstract class PlayerBackpackMixin {
    @Inject(method = "destroyVanishingCursedItems", at = @At("TAIL"))
    private void siftec$vanishFromBackpack(CallbackInfo ci) {
        Player player = (Player) (Object) this;
        if (player.level().isClientSide()) return;
        NonNullList<ItemStack> items = player.getAttachedOrCreate(Backpack.CONTENTS).items;
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isEmpty() && EnchantmentHelper.has(items.get(i), EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) {
                items.set(i, ItemStack.EMPTY);
            }
        }
    }
}
