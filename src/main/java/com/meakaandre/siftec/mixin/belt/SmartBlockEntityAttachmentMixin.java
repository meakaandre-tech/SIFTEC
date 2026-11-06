package com.meakaandre.siftec.mixin.belt;

import com.meakaandre.siftec.belt.BeltAttachments;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create's block entities as built-in belt attachments: each knows the belt it lives in (null for every real one), is
 * saved through Create's own write and read, and a belt removed for good drops its attachment.
 */
@Mixin(value = SmartBlockEntity.class, remap = false)
public abstract class SmartBlockEntityAttachmentMixin implements BeltAttachments.Hosted, BeltAttachments.Saving {
    @Unique
    private @Nullable BeltBlockEntity siftec$host;

    @Shadow
    protected abstract void write(ValueOutput view, boolean clientPacket);

    @Shadow
    protected abstract void read(ValueInput view, boolean clientPacket);

    @Override
    public @Nullable BeltBlockEntity siftec$host() {
        return siftec$host;
    }

    @Override
    public void siftec$host(@Nullable BeltBlockEntity host) {
        siftec$host = host;
    }

    @Override
    public void siftec$write(ValueOutput view, boolean clientPacket) {
        write(view, clientPacket);
    }

    @Override
    public void siftec$read(ValueInput view, boolean clientPacket) {
        read(view, clientPacket);
    }

    /** Create calls remove() when the block goes for good (not when its chunk unloads). */
    @Inject(method = "remove", at = @At("HEAD"))
    private void siftec$dropAttachment(CallbackInfo ci) {
        if ((Object) this instanceof BeltBlockEntity belt) BeltAttachments.beltRemoved(belt);
    }
}
