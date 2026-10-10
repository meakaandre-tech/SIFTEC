package com.meakaandre.siftec.mixin.client;

import com.meakaandre.siftec.backpack.BackpackRows;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The client's half of the server's {@code initMenu}: a window opened by the server gets the backpack rows even when
 * its screen is not a container screen (container screens already add them when they are made).
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientOpenBackpackMixin {
    @Inject(method = "handleOpenScreen", at = @At("TAIL"))
    private void siftec$backpackRows(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.containerMenu.containerId == packet.getContainerId()) {
            BackpackRows.attach(minecraft.player.containerMenu, minecraft.player);
        }
    }
}
