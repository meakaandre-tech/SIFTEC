package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.backpack.BackpackRows;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every window the server opens for a player (vanilla's, Create's, other mods') gets the backpack rows if it can. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerBackpackMixin {
    @Inject(method = "initMenu", at = @At("HEAD"))
    private void siftec$backpackRows(AbstractContainerMenu menu, CallbackInfo ci) {
        BackpackRows.attach(menu, (ServerPlayer) (Object) this);
    }
}
