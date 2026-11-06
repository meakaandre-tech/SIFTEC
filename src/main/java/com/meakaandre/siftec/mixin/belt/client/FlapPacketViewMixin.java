package com.meakaandre.siftec.mixin.belt.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.meakaandre.siftec.belt.BeltAttachments;
import com.zurrtum.create.client.AllHandle;
import com.zurrtum.create.infrastructure.packet.s2c.FunnelFlapPacket;
import com.zurrtum.create.infrastructure.packet.s2c.TunnelFlapPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import org.spongepowered.asm.mixin.Mixin;

/** A built-in funnel's or tunnel's flap packet finds it: Create looks it up at the cell above the belt. */
@Mixin(value = AllHandle.class, remap = false)
public abstract class FlapPacketViewMixin {
    @WrapMethod(method = "onTunnelFlap")
    private void siftec$tunnelInView(TunnelFlapPacket packet, Operation<Void> original) {
        BeltAttachments.run(() -> original.call(packet));
    }

    @WrapMethod(method = "onFunnelFlap")
    private void siftec$funnelInView(ClientGamePacketListener listener, FunnelFlapPacket packet, Operation<Void> original) {
        BeltAttachments.run(() -> original.call(listener, packet));
    }
}
