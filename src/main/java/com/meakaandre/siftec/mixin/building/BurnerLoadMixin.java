package com.meakaandre.siftec.mixin.building;

import com.meakaandre.siftec.building.BurnerLoad;
import com.zurrtum.create.content.processing.burner.BlazeBurnerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A generator's Blaze Burner burns its fuel down at the share of the generator's power the grid draws ({@link BurnerLoad}):
 * Create takes one tick of burn time a tick; here the tick's share is added back before it does.
 */
@Mixin(value = BlazeBurnerBlockEntity.class, remap = false)
public abstract class BurnerLoadMixin implements BurnerLoad {
    @Shadow
    protected int remainingBurnTime;

    @Shadow
    public boolean isCreative;

    @Unique
    private float siftec$load = -1, siftec$carry;

    @Override
    public void siftec$load(float load) {
        siftec$load = load;
    }

    @Override
    public float siftec$load() {
        return siftec$load;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void siftec$burnForLoad(CallbackInfo ci) {
        BlazeBurnerBlockEntity self = (BlazeBurnerBlockEntity) (Object) this;
        if (siftec$load < 0 || isCreative || remainingBurnTime <= 0 || self.getLevel() == null || self.getLevel().isClientSide()) return;
        siftec$carry += Math.min(1, siftec$load);
        int burnt = (int) siftec$carry;
        siftec$carry -= burnt;
        // Create takes one away after this
        remainingBurnTime += 1 - burnt;
    }
}
