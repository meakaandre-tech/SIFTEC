package com.meakaandre.siftec.mixin.steel;

import com.meakaandre.siftec.compat.SteelCasing;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Create's encased shaft and cogwheel block entities may stand in SIFTEC's steel-encased shaft and cogwheels. */
@Mixin(BlockEntityType.class)
public abstract class SteelEncasedTypeMixin {
    @Inject(method = "isValid", at = @At("HEAD"), cancellable = true)
    private void siftec$steelEncased(BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (SteelCasing.typeOf(state.getBlock()) == (Object) this) cir.setReturnValue(true);
    }
}
