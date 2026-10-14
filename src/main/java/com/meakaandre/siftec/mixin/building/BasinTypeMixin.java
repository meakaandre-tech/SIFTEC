package com.meakaandre.siftec.mixin.building;

import com.meakaandre.siftec.building.BrassBasinBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Create's Basin block entity may stand in a Brass Basin. */
@Mixin(BlockEntityType.class)
public abstract class BasinTypeMixin {
    @Inject(method = "isValid", at = @At("HEAD"), cancellable = true)
    private void siftec$brassBasin(BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (state.getBlock() instanceof BrassBasinBlock && (Object) this == com.zurrtum.create.AllBlockEntityTypes.BASIN) cir.setReturnValue(true);
    }
}
