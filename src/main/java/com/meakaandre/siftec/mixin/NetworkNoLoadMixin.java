package com.meakaandre.siftec.mixin;

import com.zurrtum.create.content.kinetics.KineticNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Create sums a network's capacity and stress by looking every member up in the world, to drop the ones that are
 * gone. A member whose chunk has unloaded was loaded again from disk right there, on the spot, at every change of
 * stress anywhere on the network (a Power Storage or a Geyser Engine changes it every few seconds). Here a member
 * in an unloaded chunk counts as gone without loading it, which is what the lookup found anyway once loaded: a
 * new block entity, not the old one. It comes back into the network when its chunk loads again.
 */
@Mixin(value = KineticNetwork.class, remap = false)
public abstract class NetworkNoLoadMixin {
    @Redirect(method = {"calculateCapacity", "calculateStress"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"))
    private BlockEntity siftec$noLoad(Level level, BlockPos pos) {
        return level.isLoaded(pos) ? level.getBlockEntity(pos) : null;
    }
}
