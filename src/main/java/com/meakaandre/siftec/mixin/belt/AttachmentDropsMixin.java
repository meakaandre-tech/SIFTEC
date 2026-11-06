package com.meakaandre.siftec.mixin.belt;

import com.meakaandre.siftec.belt.BeltAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** What a built-in funnel or tunnel drops as it comes off lands on its belt block, not in the (maybe solid) cell above. */
@Mixin(ServerLevel.class)
public abstract class AttachmentDropsMixin {
    @Inject(method = "addFreshEntity", at = @At("HEAD"))
    private void siftec$dropOnBelt(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof ItemEntity)) return;
        BlockPos to = BeltAttachments.dropRedirect(entity.blockPosition());
        if (to != null) entity.setPos(to.getX() + 0.5, to.getY() + 0.25, to.getZ() + 0.5);
    }
}
