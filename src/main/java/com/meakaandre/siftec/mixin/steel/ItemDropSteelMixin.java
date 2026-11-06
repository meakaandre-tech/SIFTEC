package com.meakaandre.siftec.mixin.steel;

import com.meakaandre.siftec.compat.Replaced;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A Gunsmithing steel or Create industrial iron drop is SIFTEC's steel the moment it is added to the world, even in a chunk
 * whose entities are not tracked yet (Replaced's ENTITY_LOAD covers drops loaded from disk). */
@Mixin(ServerLevel.class)
public abstract class ItemDropSteelMixin {
    @Inject(method = "addFreshEntity", at = @At("HEAD"))
    private void siftec$steelDrop(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof ItemEntity drop) Replaced.convert(drop, (ServerLevel) (Object) this);
    }
}
