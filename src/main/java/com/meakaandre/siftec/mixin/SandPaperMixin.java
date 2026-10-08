package com.meakaandre.siftec.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.meakaandre.siftec.owner.Ownership;
import com.zurrtum.create.content.equipment.sandPaper.SandPaperItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/** Polishing with Sand Paper in the hand follows the player's company's unlocks, as the Deployer does. */
@Mixin(value = SandPaperItem.class, remap = false)
public abstract class SandPaperMixin {
    @WrapMethod(method = "finishUsingItem")
    private ItemStack siftec$asPlayer(ItemStack stack, Level level, LivingEntity user, Operation<ItemStack> original) {
        ServerPlayer before = Ownership.asking(user instanceof ServerPlayer server ? server : null);
        try {
            return original.call(stack, level, user);
        } finally {
            Ownership.asking(before);
        }
    }
}
