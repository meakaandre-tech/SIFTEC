package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.tweak.ShaftlessBelts;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A belt end laid in air, with no shaft (see {@link ShaftlessBelts}), saved with the belt: it has no pulley, so breaking
 * the belt neither drops a shaft for it nor leaves one where it was.
 */
@Mixin(value = BeltBlockEntity.class, remap = false)
public abstract class BeltShaftlessEndMixin implements ShaftlessBelts.End {
    @Unique
    private boolean siftec$noShaft;

    @Override
    public boolean siftec$noShaft() {
        return siftec$noShaft;
    }

    @Override
    public void siftec$noShaft(boolean noShaft) {
        siftec$noShaft = noShaft;
    }

    @Inject(method = "hasPulley", at = @At("HEAD"), cancellable = true)
    private void siftec$noPulley(CallbackInfoReturnable<Boolean> cir) {
        if (siftec$noShaft) cir.setReturnValue(false);
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void siftec$writeNoShaft(ValueOutput view, boolean clientPacket, CallbackInfo ci) {
        if (siftec$noShaft) view.putBoolean("SiftecNoShaft", true);
    }

    @Inject(method = "read", at = @At("TAIL"))
    private void siftec$readNoShaft(ValueInput view, boolean clientPacket, CallbackInfo ci) {
        siftec$noShaft = view.getBooleanOr("SiftecNoShaft", false);
    }
}
