package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.owner.Ownership;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Whatever a player's click builds (a block, a belt, a whole multi-block) is marked as their company's. */
@Mixin(ServerPlayerGameMode.class)
public abstract class GameModeMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void siftec$begin(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        Ownership.acting(player);
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void siftec$end(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        Ownership.acting(null);
    }
}
