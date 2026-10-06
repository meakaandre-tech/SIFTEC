package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.tweak.NetherWater;
import com.zurrtum.create.content.fluids.tank.FluidTankBlockEntity;
import com.zurrtum.create.foundation.fluid.FluidTank;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Water in a Create Fluid Tank boils away in the Nether. */
@Mixin(value = FluidTankBlockEntity.class, remap = false)
public abstract class FluidTankMixin {
    @Shadow
    protected FluidTank tankInventory;

    @Shadow
    public abstract boolean isController();

    @Inject(method = "tick", at = @At("TAIL"))
    private void siftec$boil(CallbackInfo ci) {
        FluidTankBlockEntity self = (FluidTankBlockEntity) (Object) this;
        if (isController() && tankInventory != null) NetherWater.boil(self.getLevel(), self.getBlockPos(), tankInventory, tankInventory.getFluid());
    }
}
