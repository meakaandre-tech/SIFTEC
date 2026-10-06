package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.tweak.NetherWater;
import com.zurrtum.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Water in spouts, basins, drains and other Create fluid machines boils away in the Nether. */
@Mixin(value = SmartFluidTankBehaviour.class, remap = false)
public abstract class SmartTankMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void siftec$boil(CallbackInfo ci) {
        SmartFluidTankBehaviour self = (SmartFluidTankBehaviour) (Object) this;
        if (!NetherWater.hot(self.getLevel())) return;
        for (SmartFluidTankBehaviour.TankSegment segment : self.getTanks()) NetherWater.boil(self.getLevel(), self.getPos(), segment, segment.getFluid());
    }
}
