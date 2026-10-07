package com.meakaandre.siftec.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.meakaandre.siftec.owner.Ownership;
import com.zurrtum.create.api.behaviour.BlockEntityBehaviour;
import com.zurrtum.create.content.kinetics.belt.behaviour.BeltProcessingBehaviour;
import com.zurrtum.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.zurrtum.create.content.kinetics.belt.transport.TransportedItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A Press, Deployer or Spout working on a belt or depot is run from the belt's tick. While it works, recipe
 * lookups and Somersloops go by the machine, not by the belt under it.
 */
@Mixin(value = BeltProcessingBehaviour.class, remap = false)
public abstract class BeltProcessingMixin {
    @WrapMethod(method = {"handleReceivedItem", "handleHeldItem"})
    private BeltProcessingBehaviour.ProcessingResult siftec$asMachine(TransportedItemStack stack, TransportedItemStackHandlerBehaviour inventory,
                                                                     Operation<BeltProcessingBehaviour.ProcessingResult> original) {
        BlockEntity before = Ownership.ticking();
        Ownership.ticking(((BlockEntityBehaviour<?>) (Object) this).blockEntity);
        try {
            return original.call(stack, inventory);
        } finally {
            Ownership.ticking(before);
        }
    }
}
