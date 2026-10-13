package com.meakaandre.siftec.mixin.building;

import com.meakaandre.siftec.building.BasinPace;
import com.zurrtum.create.content.processing.basin.BasinOperatingBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A Press or Mixer inside a building waits out its building's last recipe before it looks for the next (the check
 * is tried again every tick until then), and tells the building when it starts one.
 */
@Mixin(value = BasinOperatingBlockEntity.class, remap = false)
public abstract class BasinPaceMixin {
    @Inject(method = "updateBasin", at = @At("HEAD"), cancellable = true)
    private void siftec$wait(CallbackInfoReturnable<Boolean> cir) {
        if (BasinPace.blocked((BlockEntity) (Object) this)) cir.setReturnValue(false);
    }

    @Inject(method = "startProcessingBasin", at = @At("HEAD"))
    private void siftec$started(CallbackInfo ci) {
        BasinPace.started((BlockEntity) (Object) this);
    }
}
