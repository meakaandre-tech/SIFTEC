package com.meakaandre.siftec.mixin.power;

import com.meakaandre.siftec.power.PowerGrids;
import com.meakaandre.siftec.power.Wires;
import net.minecraft.world.level.Level;
import org.antarcticgardens.cna.content.electricity.connector.AbstractElectricalConnector;
import org.antarcticgardens.cna.content.electricity.wire.WireType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every New Age connector is on a SIFTEC power grid, and its wires are kept in the {@link com.meakaandre.siftec.power.WireMap}. */
@Mixin(value = AbstractElectricalConnector.class, remap = false)
public abstract class ConnectorMixin {
    @Inject(method = "serverTick", at = @At("HEAD"))
    private void siftec$onGrid(CallbackInfo ci) {
        PowerGrids.seen((AbstractElectricalConnector) (Object) this);
    }

    @Inject(method = "connect", at = @At("TAIL"))
    private void siftec$linked(AbstractElectricalConnector other, WireType wireType, CallbackInfo ci) {
        Wires.linked((AbstractElectricalConnector) (Object) this, other);
    }

    @Inject(method = "disconnect", at = @At("TAIL"))
    private void siftec$unlinked(AbstractElectricalConnector other, CallbackInfo ci) {
        Wires.unlinked((AbstractElectricalConnector) (Object) this, other);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void siftec$removed(Level level, CallbackInfo ci) {
        Wires.removed((AbstractElectricalConnector) (Object) this, level);
    }
}
