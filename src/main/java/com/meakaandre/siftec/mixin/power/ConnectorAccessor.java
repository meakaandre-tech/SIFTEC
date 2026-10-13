package com.meakaandre.siftec.mixin.power;

import net.minecraft.core.BlockPos;
import org.antarcticgardens.cna.content.electricity.connector.AbstractElectricalConnector;
import org.antarcticgardens.cna.content.electricity.wire.WireType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** New Age's own lists of a connector's wires: by far end (loaded or not), and by loaded connector. */
@Mixin(value = AbstractElectricalConnector.class, remap = false)
public interface ConnectorAccessor {
    @Accessor("connectorPositions")
    Map<BlockPos, WireType> siftec$positions();

    @Accessor("connectors")
    Map<AbstractElectricalConnector, WireType> siftec$connectors();
}
