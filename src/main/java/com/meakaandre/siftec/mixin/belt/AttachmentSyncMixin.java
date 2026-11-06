package com.meakaandre.siftec.mixin.belt;

import com.meakaandre.siftec.belt.BeltAttachments;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.foundation.blockEntity.SyncedBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A built-in attachment's data goes to clients with its belt's (its own cell holds some other block). */
@Mixin(value = SyncedBlockEntity.class, remap = false)
public abstract class AttachmentSyncMixin {
    @Inject(method = "sendData", at = @At("HEAD"), cancellable = true)
    private void siftec$sendWithBelt(CallbackInfo ci) {
        BeltBlockEntity host = BeltAttachments.hostOf((SyncedBlockEntity) (Object) this);
        if (host == null) return;
        host.sendData();
        ci.cancel();
    }
}
