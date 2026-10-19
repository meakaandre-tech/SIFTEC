package com.meakaandre.siftec.mixin.client;

import com.zurrtum.create.client.content.kinetics.mixer.MixerVisual;
import com.zurrtum.create.client.foundation.blockEntity.behaviour.animation.AnimationBehaviour;
import com.zurrtum.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create Fly races here: Flywheel can build a Mechanical Mixer's visual before the block entity's first client tick has
 * added its behaviours, and the visual's head animation then reads a null animation behaviour (a NullPointerException
 * in the client log). Until the block entity has it, the visual skips animating the head; it animates normally from the
 * next frame on.
 * <p>
 * A stop-gap: the proper fix belongs in the Create-Fly fork (meakaandre-tech/Create-Fly), where the visual should not
 * be built, or should not animate, before the block entity has its behaviours.
 */
@Mixin(value = MixerVisual.class, remap = false)
public abstract class MixerVisualMixin {
    @Inject(method = "animate", at = @At("HEAD"), cancellable = true)
    private void siftec$notReady(float partialTick, CallbackInfo ci) {
        if (siftec$noBehaviour()) ci.cancel();
    }

    @Inject(method = "transformHead", at = @At("HEAD"), cancellable = true)
    private void siftec$notReadyHead(float offset, float partialTick, CallbackInfo ci) {
        if (siftec$noBehaviour()) ci.cancel();
    }

    @Unique
    private boolean siftec$noBehaviour() {
        BlockEntity be = ((VisualBlockEntityAccessor) (Object) this).siftec$blockEntity();
        return !(be instanceof MechanicalMixerBlockEntity mixer) || mixer.getBehaviour(AnimationBehaviour.TYPE) == null;
    }
}
