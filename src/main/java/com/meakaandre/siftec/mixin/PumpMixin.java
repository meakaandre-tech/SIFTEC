package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.owner.Upgrades;
import com.zurrtum.create.content.fluids.FluidPropagator;
import com.zurrtum.create.content.fluids.pump.PumpBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Pipeline Engineering Mk.2 doubles the range and pressure of the company's Mechanical Pumps. */
@Mixin(value = PumpBlockEntity.class, remap = false)
public abstract class PumpMixin {
    @Redirect(method = "distributePressureTo", at = @At(value = "INVOKE", target = "Lcom/zurrtum/create/content/fluids/FluidPropagator;getPumpRange()I"))
    private int siftec$range() {
        return FluidPropagator.getPumpRange() * Upgrades.pumps((PumpBlockEntity) (Object) this);
    }

    // the method's only float local; by ordinal, so it works on a Create Fly built without local variable names
    @ModifyVariable(method = "distributePressureTo", at = @At("STORE"), ordinal = 0)
    private float siftec$pressure(float pressure) {
        return pressure * Upgrades.pumps((PumpBlockEntity) (Object) this);
    }
}
