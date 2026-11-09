package com.meakaandre.siftec.mixin.belt;

import com.zurrtum.create.content.kinetics.belt.BeltBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A belt (not encased) hides none of its neighbours' faces. Its block shape is the low belt's, whose underside fills the
 * block, so by default the ground's top face under every belt went undrawn; the belt itself is drawn by its renderer, a
 * pixel in from the block's edge in places, and the sky showed through that line under it.
 */
@Mixin(BlockBehaviour.class)
public abstract class BeltOcclusionMixin {
    @Inject(method = "getOcclusionShape", at = @At("HEAD"), cancellable = true)
    private void siftec$beltHidesNothing(BlockState state, CallbackInfoReturnable<VoxelShape> cir) {
        if (state.getBlock() instanceof BeltBlock && !state.getValue(BeltBlock.CASING)) cir.setReturnValue(Shapes.empty());
    }
}
