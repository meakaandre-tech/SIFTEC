package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.tweak.NetherWater;
import com.zurrtum.create.content.fluids.tank.FluidTankBlockEntity;
import com.zurrtum.create.foundation.fluid.FluidTank;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Water in a Create Fluid Tank boils away in the Nether; and a company's tanks stay one block wide until Petroleum
 * Power ({@link com.meakaandre.siftec.tweak.TankSizes}).
 */
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

    @Inject(method = "getMaxWidth", at = @At("HEAD"), cancellable = true)
    private void siftec$petroleum(CallbackInfoReturnable<Integer> cir) {
        if (com.meakaandre.siftec.tweak.TankSizes.small((FluidTankBlockEntity) (Object) this)) cir.setReturnValue(1);
    }
}
