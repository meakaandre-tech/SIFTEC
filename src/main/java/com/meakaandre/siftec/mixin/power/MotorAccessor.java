package com.meakaandre.siftec.mixin.power;

import org.antarcticgardens.cna.content.motor.MotorBlockEntity;
import org.antarcticgardens.cna.content.motor.variants.IMotorVariant;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A New Age motor's kind (its SU and speed), for what it asks of the grid. */
@Mixin(value = MotorBlockEntity.class, remap = false)
public interface MotorAccessor {
    @Accessor("variant")
    IMotorVariant siftec$variant();
}
