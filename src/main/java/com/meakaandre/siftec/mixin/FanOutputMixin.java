package com.meakaandre.siftec.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meakaandre.siftec.owner.Boosts;
import com.zurrtum.create.content.kinetics.fan.processing.FanProcessing;
import com.zurrtum.create.content.kinetics.fan.processing.FanProcessingType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * An Encased Fan with a Somersloop doubles what it processes, whatever the kind (washing and haunting go through
 * Create's output rolls, smoking and blasting do not): the processed list is doubled once, the rolls inside are
 * left alone. Fan processing runs from the fan's own tick, so the fan is the machine asking.
 */
@Mixin(value = FanProcessing.class, remap = false)
public abstract class FanOutputMixin {
    @WrapOperation(method = {
        "applyProcessing(Lnet/minecraft/world/entity/item/ItemEntity;Lcom/zurrtum/create/content/kinetics/fan/processing/FanProcessingType;)Z",
        "applyProcessing(Lcom/zurrtum/create/content/kinetics/belt/transport/TransportedItemStack;Lnet/minecraft/world/level/Level;Lcom/zurrtum/create/content/kinetics/fan/processing/FanProcessingType;)Lcom/zurrtum/create/content/kinetics/belt/behaviour/TransportedItemStackHandlerBehaviour$TransportedResult;"
    }, require = 2, at = @At(value = "INVOKE",
        target = "Lcom/zurrtum/create/content/kinetics/fan/processing/FanProcessingType;process(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;)Ljava/util/List;"))
    private static List<ItemStack> siftec$doubleProcessed(FanProcessingType type, ItemStack stack, Level level, Operation<List<ItemStack>> original) {
        boolean doubling = Boosts.doubling();
        List<ItemStack> results = Boosts.withoutDoubling(() -> original.call(type, stack, level));
        return doubling && results != null && !results.isEmpty() ? Boosts.doubled(results, null) : results;
    }
}
