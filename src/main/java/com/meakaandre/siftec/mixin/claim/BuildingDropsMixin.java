package com.meakaandre.siftec.mixin.claim;

import com.meakaandre.siftec.building.BuildingProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A Build Gun building's block that is lost anyway (anything but the Build Gun took it) gives nothing back for itself:
 * the drops of a block still listed as a building's ({@link BuildingProtection#protectedAt}) never appear. The building then
 * comes down as before, with its refund and contents; only the lost block's own item is not given on top of that.
 */
@Mixin(Block.class)
public abstract class BuildingDropsMixin {
    @Inject(method = "popResource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)V",
        at = @At("HEAD"), cancellable = true)
    private static void siftec$noBuildingDrops(Level level, BlockPos pos, ItemStack stack, CallbackInfo ci) {
        if (!level.isClientSide() && BuildingProtection.protectedAt(level, pos)) ci.cancel();
    }

    @Inject(method = "popResourceFromFace(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/item/ItemStack;)V",
        at = @At("HEAD"), cancellable = true)
    private static void siftec$noBuildingDropsFromFace(Level level, BlockPos pos, Direction face, ItemStack stack, CallbackInfo ci) {
        if (!level.isClientSide() && BuildingProtection.protectedAt(level, pos)) ci.cancel();
    }
}
