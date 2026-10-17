package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.sim.AlwaysTests;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.level.TicketStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** TEMP: who adds a ticket to twin B's chunks while it should be left alone. */
@Mixin(TicketStorage.class)
public abstract class TicketTraceMixin {
    @Inject(method = "addTicket(JLnet/minecraft/server/level/Ticket;)Z", at = @At("HEAD"))
    private void siftec$trace(long pos, Ticket ticket, CallbackInfoReturnable<Boolean> cir) {
        AlwaysTests.ticketAdded(pos, ticket);
    }
}
