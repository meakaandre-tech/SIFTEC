package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.tweak.Pollution;
import com.zurrtum.create.content.processing.burner.BlazeBurnerBlock;
import com.zurrtum.create.content.processing.burner.BlazeBurnerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A Blaze Burner that is burning fuel smokes like the pack's engines do. */
@Mixin(value = BlazeBurnerBlockEntity.class, remap = false)
public abstract class BlazeBurnerMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void siftec$smoke(CallbackInfo ci) {
        BlazeBurnerBlockEntity self = (BlazeBurnerBlockEntity) (Object) this;
        if (self.getLevel() == null || self.getLevel().isClientSide() || (self.getLevel().getGameTime() + self.getBlockPos().hashCode()) % 20 != 0) return;
        if (!self.isCreative() && self.getHeatLevelFromBlock().isAtLeast(BlazeBurnerBlock.HeatLevel.FADING)) Pollution.burning(self.getLevel(), self.getBlockPos());
    }
}
