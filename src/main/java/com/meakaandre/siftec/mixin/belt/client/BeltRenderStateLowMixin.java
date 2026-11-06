package com.meakaandre.siftec.mixin.belt.client;

import com.meakaandre.siftec.client.belt.LowBeltRender;
import com.zurrtum.create.client.content.kinetics.belt.BeltRenderer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Carries the one-block belt's extra parts from extract to submit (see {@link LowBeltRender}). */
@Mixin(value = BeltRenderer.BeltRenderState.class, remap = false)
public abstract class BeltRenderStateLowMixin implements LowBeltRender.State {
    @Unique
    private LowBeltRender.@Nullable Parts siftec$low;

    @Override
    public LowBeltRender.@Nullable Parts siftec$low() {
        return siftec$low;
    }

    @Override
    public void siftec$low(LowBeltRender.@Nullable Parts parts) {
        siftec$low = parts;
    }
}
