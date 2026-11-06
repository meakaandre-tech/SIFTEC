package com.meakaandre.siftec.mixin.belt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.meakaandre.siftec.belt.BeltAttachments;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every belt block can hold a built-in funnel or tunnel (see {@link BeltAttachments}): kept here, saved and sent with
 * the belt, and ticked inside the belt's tick, which runs in the stacked view so Create's belt code finds it.
 */
@Mixin(value = BeltBlockEntity.class, remap = false)
public abstract class BeltAttachmentHostMixin implements BeltAttachments.Host {
    @Unique
    private @Nullable SmartBlockEntity siftec$attachment;

    @Override
    public @Nullable SmartBlockEntity siftec$attachment() {
        return siftec$attachment;
    }

    @Override
    public void siftec$attachment(@Nullable SmartBlockEntity attachment) {
        siftec$attachment = attachment;
    }

    @WrapMethod(method = "tick")
    private void siftec$tickInView(Operation<Void> original) {
        BeltAttachments.run(() -> {
            original.call();
            BeltAttachments.tick((BeltBlockEntity) (Object) this);
        });
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void siftec$writeAttachment(ValueOutput view, boolean clientPacket, CallbackInfo ci) {
        BeltAttachments.write((BeltBlockEntity) (Object) this, view, clientPacket);
    }

    // HEAD: Create's read returns early for a load from disk
    @Inject(method = "read", at = @At("HEAD"))
    private void siftec$readAttachment(ValueInput view, boolean clientPacket, CallbackInfo ci) {
        BeltAttachments.read((BeltBlockEntity) (Object) this, view, clientPacket);
    }
}
