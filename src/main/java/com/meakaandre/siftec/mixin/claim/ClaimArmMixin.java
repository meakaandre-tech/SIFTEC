package com.meakaandre.siftec.mixin.claim;

import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.claim.Claims;
import com.zurrtum.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.zurrtum.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A Mechanical Arm takes from and puts into a point only where a funnel could: never across someone else's claim border
 * ({@link Claims#crosses}), and never into a building's machines or controller from outside it ({@link Buildings#sealed}:
 * a building is fed and emptied only at its ports).
 */
@Mixin(value = ArmInteractionPoint.class, remap = false)
public abstract class ClaimArmMixin {
    @Shadow
    protected Level level;
    @Shadow
    protected BlockPos pos;

    @Unique
    private boolean siftec$refused(ArmBlockEntity arm) {
        if (level == null || level.isClientSide() || arm == null || pos == null) return false;
        BlockPos from = arm.getBlockPos();
        return Claims.crosses(level, from, pos) || Buildings.sealed(level, from, pos);
    }

    @Inject(method = "insert", at = @At("HEAD"), cancellable = true)
    private void siftec$insert(ArmBlockEntity arm, ItemStack stack, boolean simulate, CallbackInfoReturnable<ItemStack> cir) {
        if (siftec$refused(arm)) cir.setReturnValue(stack);
    }

    @Inject(method = "extract(Lcom/zurrtum/create/content/kinetics/mechanicalArm/ArmBlockEntity;IIZ)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"), cancellable = true)
    private void siftec$extract(ArmBlockEntity arm, int slot, int amount, boolean simulate, CallbackInfoReturnable<ItemStack> cir) {
        if (siftec$refused(arm)) cir.setReturnValue(ItemStack.EMPTY);
    }

    @Inject(method = "extract(Lcom/zurrtum/create/content/kinetics/mechanicalArm/ArmBlockEntity;IZ)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"), cancellable = true)
    private void siftec$extractSlot(ArmBlockEntity arm, int slot, boolean simulate, CallbackInfoReturnable<ItemStack> cir) {
        if (siftec$refused(arm)) cir.setReturnValue(ItemStack.EMPTY);
    }

    @Inject(method = "getSlotCount", at = @At("HEAD"), cancellable = true)
    private void siftec$slots(ArmBlockEntity arm, CallbackInfoReturnable<Integer> cir) {
        if (siftec$refused(arm)) cir.setReturnValue(0);
    }
}
