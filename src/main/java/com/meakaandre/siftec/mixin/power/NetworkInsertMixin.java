package com.meakaandre.siftec.mixin.power;

import com.meakaandre.siftec.power.PowerGrids;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import org.antarcticgardens.cna.content.electricity.connector.AbstractElectricalConnector;
import org.antarcticgardens.cna.content.electricity.network.NetworkEnergyStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Energy a New Age block pushes into a connector (carbon brushes beside one) goes to the connector's grid. */
@Mixin(value = NetworkEnergyStorage.class, remap = false)
public abstract class NetworkInsertMixin {
    @Shadow
    @Final
    private AbstractElectricalConnector connector;

    @Inject(method = "insert", at = @At("HEAD"), cancellable = true)
    private void siftec$toGrid(long maxAmount, TransactionContext txn, CallbackInfoReturnable<Long> cir) {
        cir.setReturnValue(PowerGrids.pushed(connector, maxAmount, txn));
    }
}
