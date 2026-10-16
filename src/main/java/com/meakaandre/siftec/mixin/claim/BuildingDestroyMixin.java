package com.meakaandre.siftec.mixin.claim;

import com.meakaandre.siftec.building.Buildings;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Nothing breaks a Build Gun building's block through the game's block-breaking call: a Wither's charge, a mob, an item
 * or another mod's tool alike. Buildings come down only with the Build Gun, which takes its blocks away itself.
 */
@Mixin(Level.class)
public abstract class BuildingDestroyMixin {
    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z", at = @At("HEAD"), cancellable = true)
    private void siftec$keepBuildings(BlockPos pos, boolean drop, Entity breaker, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (!self.isClientSide() && Buildings.protectedAt(self, pos)) cir.setReturnValue(false);
    }
}
