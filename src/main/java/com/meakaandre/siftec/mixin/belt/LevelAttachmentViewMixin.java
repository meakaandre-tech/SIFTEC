package com.meakaandre.siftec.mixin.belt;

import com.meakaandre.siftec.belt.BeltAttachments;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The stacked view (see {@link BeltAttachments}): while Create's code for a belt runs, the cell above a belt block with
 * a built-in funnel or tunnel reads as that funnel or tunnel, and what Create writes there changes it. Outside the view
 * (and on any thread not in it) nothing changes: one plain field read.
 */
@Mixin(Level.class)
public abstract class LevelAttachmentViewMixin {
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void siftec$viewState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (!BeltAttachments.viewing()) return;
        BeltBlockEntity host = BeltAttachments.hostAt((Level) (Object) this, pos);
        if (host != null) cir.setReturnValue(BeltAttachments.stateOf(host));
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void siftec$viewBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        if (!BeltAttachments.viewing()) return;
        BeltBlockEntity host = BeltAttachments.hostAt((Level) (Object) this, pos);
        if (host != null) {
            SmartBlockEntity a = BeltAttachments.of(host);
            cir.setReturnValue(a);
        }
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"), cancellable = true)
    private void siftec$viewSetBlock(BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        if (!BeltAttachments.viewing()) return;
        BeltBlockEntity host = BeltAttachments.hostAt((Level) (Object) this, pos);
        if (host == null) return;
        BeltAttachments.written(host, state);
        cir.setReturnValue(true);
    }

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z", at = @At("HEAD"), cancellable = true)
    private void siftec$viewDestroy(BlockPos pos, boolean drop, Entity breaker, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        if (!BeltAttachments.viewing()) return;
        BeltBlockEntity host = BeltAttachments.hostAt((Level) (Object) this, pos);
        if (host == null) return;
        BeltAttachments.destroyed(host, drop);
        cir.setReturnValue(true);
    }
}
