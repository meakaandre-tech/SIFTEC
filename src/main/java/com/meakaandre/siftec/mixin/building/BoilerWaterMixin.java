package com.meakaandre.siftec.mixin.building;

import com.meakaandre.siftec.building.BoilerWater;
import com.zurrtum.create.content.fluids.tank.BoilerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A generator building's boiler takes Satisfactory's water ({@link BoilerWater}): the heat levels its water supply allows
 * are the water it has been given over what one level takes there, instead of Create's 810 droplets a tick a level. The
 * boiler still measures what really goes into it, the way Create does.
 */
@Mixin(value = BoilerData.class, remap = false)
public abstract class BoilerWaterMixin implements BoilerWater {
    @Shadow
    public float waterSupply;

    @Unique
    private float siftec$perLevel = -1;

    @Override
    public void siftec$waterPerLevel(float droplets) {
        siftec$perLevel = droplets;
    }

    @Override
    public float siftec$waterPerLevel() {
        return siftec$perLevel;
    }

    @Inject(method = "getMaxHeatLevelForWaterSupply", at = @At("HEAD"), cancellable = true)
    private void siftec$satisfactoryWater(CallbackInfoReturnable<Integer> cir) {
        if (siftec$perLevel < 0) return;
        if (siftec$perLevel == 0) {
            cir.setReturnValue(18);
            return;
        }
        if (Float.isInfinite(siftec$perLevel)) {
            cir.setReturnValue(0);
            return;
        }
        cir.setReturnValue(Math.min(18, (int) Math.floor(waterSupply / siftec$perLevel + 0.05f)));
    }
}
