package com.meakaandre.siftec.mixin;

import com.zurrtum.create.content.kinetics.RotationPropagator;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Create's "this block lost its source: clear what hung on it and look for another source". */
@Mixin(value = RotationPropagator.class, remap = false)
public interface PropagatorInvoker {
    @Invoker("propagateMissingSource")
    static void siftec$propagateMissingSource(KineticBlockEntity be) {
        throw new AssertionError();
    }
}
