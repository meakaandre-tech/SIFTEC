package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.tweak.ShaftlessBelts;
import com.zurrtum.create.content.kinetics.belt.item.BeltConnectorItem;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The belt item lays a belt between two spots of plain ground, no shafts needed (see {@link ShaftlessBelts}). */
@Mixin(value = BeltConnectorItem.class, remap = false)
public abstract class BeltConnectorShaftlessMixin {
    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void siftec$shaftless(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult result = ShaftlessBelts.use(context);
        if (result != null) cir.setReturnValue(result);
    }
}
