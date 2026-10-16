package com.meakaandre.siftec.mixin.tb;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * On death the Traveler's Backpack is neither dropped nor placed as a block: it stays in the player's attachment and
 * the mod puts it back on the respawned player. What was in it has already gone into the death crate
 * ({@link com.meakaandre.siftec.death.DeathCrates}); its upgrades stay with it.
 */
@Pseudo
@Mixin(targets = "com.tiviacz.travelersbackpack.util.BackpackDeathHelper", remap = false)
public abstract class TbDeathMixin {
    @Inject(method = "onPlayerDrops(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)Z",
        at = @At("HEAD"), cancellable = true)
    private static void siftec$keep(Level level, Player player, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
