package com.meakaandre.siftec.mixin;

import com.zurrtum.create.content.kinetics.belt.BeltBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A belt has no shaft at either end: Create joins blocks into a kinetic network only through their shafts, so no shaft,
 * cog or motor beside a belt ever turns it or is turned by it, and a belt placed on shafts is not linked to anything
 * (see {@link com.meakaandre.siftec.tweak.SelfMovingBelts}).
 */
@Mixin(value = BeltBlock.class, remap = false)
public abstract class BeltNoShaftMixin {
    @Inject(method = "hasShaftTowards", at = @At("HEAD"), cancellable = true)
    private void siftec$noShaft(LevelReader world, BlockPos pos, BlockState state, Direction face, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
