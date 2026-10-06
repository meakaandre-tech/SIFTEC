package com.meakaandre.siftec.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the Jetpack reset the server's "this player has been floating too long" counter, which would kick them. */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ConnectionAccessor {
    @Accessor("aboveGroundTickCount")
    void siftec$setAboveGroundTickCount(int ticks);
}
