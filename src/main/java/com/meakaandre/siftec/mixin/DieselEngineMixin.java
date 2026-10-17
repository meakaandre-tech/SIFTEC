package com.meakaandre.siftec.mixin;

import com.jesz.createdieselgenerators.content.diesel_engine.normal.DieselEngineBlockEntity;
import com.meakaandre.siftec.building.EngineControl;
import com.zurrtum.create.infrastructure.fluids.FluidStack;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Diesel Generators' engine inside the Fuel Generator runs at the numbers the building gives it ({@link EngineControl}):
 * the speed and SU it gives and what it burns, Satisfactory's for the fuel in it, and its fuel burns down only for the
 * share of the generator's power the grid draws.
 */
@Mixin(value = DieselEngineBlockEntity.class, remap = false)
public abstract class DieselEngineMixin implements EngineControl {
    @Unique
    private float siftec$speed = -1, siftec$strength = -1, siftec$burn = -1, siftec$load = -1;

    @Override
    public void siftec$set(float speed, float strength, float burnPerTick, float load) {
        siftec$speed = speed;
        siftec$strength = strength;
        siftec$burn = burnPerTick;
        siftec$load = load;
    }

    @Override
    public FluidStack siftec$fuel() {
        return ((DieselEngineBlockEntity) (Object) this).fs();
    }

    @Override
    public float siftec$burnPerTick() {
        return siftec$burn >= 0 ? siftec$burn : ((DieselEngineBlockEntity) (Object) this).getCachedBurnRate();
    }

    @Inject(method = "getCachedFuelSpeed", at = @At("HEAD"), cancellable = true, require = 1)
    private void siftec$speed(CallbackInfoReturnable<Float> cir) {
        if (siftec$speed > 0) cir.setReturnValue(siftec$speed);
    }

    @Inject(method = "getCachedFuelCapacity", at = @At("HEAD"), cancellable = true, require = 1)
    private void siftec$capacity(CallbackInfoReturnable<Float> cir) {
        // Diesel Generators keeps capacity a turn: the SU over the speed
        if (siftec$speed > 0 && siftec$strength > 0) cir.setReturnValue(siftec$strength / siftec$speed);
    }

    @Inject(method = "getCachedBurnRate", at = @At("HEAD"), cancellable = true, require = 1)
    private void siftec$burn(CallbackInfoReturnable<Float> cir) {
        if (siftec$burn >= 0) cir.setReturnValue(siftec$burn);
    }

    @Redirect(method = "tick", require = 1, at = @At(value = "FIELD", target = "Lcom/jesz/createdieselgenerators/content/diesel_engine/normal/DieselEngineBlockEntity;cachedBurnRate:F",
        opcode = Opcodes.GETFIELD))
    private float siftec$burnRate(DieselEngineBlockEntity engine) {
        return siftec$burn >= 0 ? siftec$burn : engine.getCachedBurnRate();
    }

    @Redirect(method = "tick", require = 1, at = @At(value = "INVOKE", target = "Lcom/jesz/createdieselgenerators/content/diesel_engine/normal/DieselEngineBlockEntity;getFuelThrottle()F"))
    private float siftec$burnForLoad(DieselEngineBlockEntity engine) {
        float throttle = engine.getFuelThrottle();
        return siftec$load < 0 ? throttle : throttle * Math.min(1, siftec$load);
    }
}
