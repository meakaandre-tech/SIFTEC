package com.meakaandre.siftec.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No fluid is poured into an empty resource node: it is growing back (node.NodeSites). */
@Mixin(BucketItem.class)
public abstract class NodeBucketMixin {
    @Inject(method = "emptyContents", at = @At("HEAD"), cancellable = true)
    private void siftec$notIntoNode(@Nullable LivingEntity user, Level level, BlockPos pos, @Nullable BlockHitResult hit, CallbackInfoReturnable<Boolean> cir) {
        if (com.meakaandre.siftec.node.NodeSites.isSite(level, pos)) cir.setReturnValue(false);
    }
}
