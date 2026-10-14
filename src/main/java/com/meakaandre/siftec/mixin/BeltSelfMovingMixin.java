package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.tweak.SelfMovingBelts;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives every belt its self-moving speed, kept up to date on the server and sent to clients (see {@link SelfMovingBelts}). */
@Mixin(value = BeltBlockEntity.class, remap = false)
public abstract class BeltSelfMovingMixin implements SelfMovingBelts.Holder {
    @Unique
    private float siftec$selfSpeed;
    @Unique
    private boolean siftec$checked;

    @Override
    public float siftec$selfSpeed() {
        return siftec$selfSpeed;
    }

    @Override
    public void siftec$selfSpeed(float speed) {
        siftec$selfSpeed = speed;
    }

    @Override
    public boolean siftec$checked() {
        return siftec$checked;
    }

    @Override
    public void siftec$checked(boolean checked) {
        siftec$checked = checked;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void siftec$selfMoving(CallbackInfo ci) {
        SelfMovingBelts.tick((BeltBlockEntity) (Object) this);
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void siftec$writeSelfSpeed(ValueOutput view, boolean clientPacket, CallbackInfo ci) {
        if (clientPacket) view.putFloat("SiftecSelfSpeed", siftec$selfSpeed);
    }

    @Inject(method = "read", at = @At("TAIL"))
    private void siftec$readSelfSpeed(ValueInput view, boolean clientPacket, CallbackInfo ci) {
        if (clientPacket) siftec$selfSpeed = view.getFloatOr("SiftecSelfSpeed", 0);
    }
}
