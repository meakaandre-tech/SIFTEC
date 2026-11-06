package com.meakaandre.siftec.mixin.belt;

import com.meakaandre.siftec.belt.BeltAttachments;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A built-in attachment that changes marks its belt's chunk to be saved, not the chunk of the cell above. */
@Mixin(BlockEntity.class)
public abstract class AttachmentChangedMixin {
    @Inject(method = "setChanged()V", at = @At("HEAD"), cancellable = true)
    private void siftec$changedWithBelt(CallbackInfo ci) {
        BeltBlockEntity host = BeltAttachments.hostOf((BlockEntity) (Object) this);
        if (host == null) return;
        host.setChanged();
        ci.cancel();
    }
}
