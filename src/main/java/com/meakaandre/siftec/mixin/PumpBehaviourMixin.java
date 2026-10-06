package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.owner.Upgrades;
import com.zurrtum.create.content.fluids.pump.PumpBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The pump's own two pipe ends get the doubled pressure too. */
@Mixin(targets = "com.zurrtum.create.content.fluids.pump.PumpBlockEntity$PumpFluidTransferBehaviour", remap = false)
public abstract class PumpBehaviourMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lcom/zurrtum/create/content/fluids/pump/PumpBlockEntity;getSpeed()F"))
    private float siftec$pressure(PumpBlockEntity pump) {
        return pump.getSpeed() * Upgrades.pumps(pump);
    }
}
