package com.meakaandre.siftec.mixin.belt;

import com.zurrtum.create.content.kinetics.belt.BeltBlock;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.content.kinetics.belt.BeltHelper;
import com.zurrtum.create.content.kinetics.belt.BeltSlope;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A vertical belt's blocks are stacked up from its controller (as Create chains them), so its offsets run up or down,
 * not along its facing: Create never moved items on a vertical belt, so it never needed this.
 */
@Mixin(value = BeltHelper.class, remap = false)
public abstract class BeltVerticalPositionMixin {
    @Inject(method = "getPositionForOffset", at = @At("HEAD"), cancellable = true)
    private static void siftec$vertical(BeltBlockEntity controller, int offset, CallbackInfoReturnable<BlockPos> cir) {
        if (controller.getBlockState().getValue(BeltBlock.SLOPE) != BeltSlope.VERTICAL) return;
        boolean up = controller.getBeltFacing().getAxisDirection() == Direction.AxisDirection.POSITIVE;
        cir.setReturnValue(controller.getBlockPos().above(up ? offset : -offset));
    }

    /** Where along a vertical belt an item is: up or down from its controller's centre (Create's ignores the height). */
    @Inject(method = "getVectorForOffset(Lcom/zurrtum/create/content/kinetics/belt/BeltBlockEntity;F)Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"), cancellable = true)
    private static void siftec$verticalVector(BeltBlockEntity controller, float offset, CallbackInfoReturnable<Vec3> cir) {
        if (controller.getBlockState().getValue(BeltBlock.SLOPE) != BeltSlope.VERTICAL) return;
        boolean up = controller.getBeltFacing().getAxisDirection() == Direction.AxisDirection.POSITIVE;
        cir.setReturnValue(Vec3.atCenterOf(controller.getBlockPos()).add(0, (up ? 1 : -1) * (offset - 0.5f), 0));
    }
}
