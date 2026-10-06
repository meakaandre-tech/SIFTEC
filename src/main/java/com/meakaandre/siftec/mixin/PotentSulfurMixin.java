package com.meakaandre.siftec.mixin;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.PotentSulfurBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Geysers no longer keep a steady beat: the wait between eruptions is about 45 seconds, anywhere from 20 to 70. */
@Mixin(PotentSulfurBlockEntity.class)
public abstract class PotentSulfurMixin {
    @Shadow
    public int waitingCountdown;

    @Inject(method = "resetCountdown", at = @At("TAIL"))
    private void siftec$unevenGeysers(CallbackInfo ci) {
        Level level = ((BlockEntity) (Object) this).getLevel();
        if (level == null) return;
        // two rolls added together: most waits land near the middle, a few near either end
        waitingCountdown = 400 + level.getRandom().nextInt(501) + level.getRandom().nextInt(501);
    }
}
