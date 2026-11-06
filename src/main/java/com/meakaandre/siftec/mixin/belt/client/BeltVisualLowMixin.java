package com.meakaandre.siftec.mixin.belt.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.meakaandre.siftec.belt.BeltShapesLow;
import com.meakaandre.siftec.client.belt.LowBeltRender;
import com.zurrtum.create.client.content.kinetics.belt.BeltVisual;
import com.zurrtum.create.client.flywheel.api.model.Model;
import com.zurrtum.create.client.flywheel.lib.model.Models;
import com.zurrtum.create.client.flywheel.lib.model.baked.PartialModel;
import com.zurrtum.create.content.kinetics.belt.BeltBlock;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.content.kinetics.belt.BeltSlope;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Create's instanced belt (Flywheel) draws the squeezed band: its model is squeezed in the model's own frame, toward the
 * side of the model that this belt's rotation puts at the bottom of a flat belt or against a vertical belt's back face.
 * Every belt block is still drawn by the belt renderer too, for its trim and attachment.
 */
@Mixin(value = BeltVisual.class, remap = false)
public abstract class BeltVisualLowMixin {
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/zurrtum/create/client/flywheel/lib/model/Models;chunkPartial(Lcom/zurrtum/create/client/flywheel/lib/model/baked/PartialModel;)Lcom/zurrtum/create/client/flywheel/api/model/Model;"))
    private Model siftec$squeezed(PartialModel partial, Operation<Model> original, @Local(argsOnly = true) BeltBlockEntity be) {
        return siftec$model(partial, be.getBlockState());
    }

    /** Every belt block is drawn by the belt renderer as well (its trim, caps and attachment), not only the belt's first. */
    @Inject(method = "shouldSkipVanillaRender", at = @At("HEAD"), cancellable = true)
    private static void siftec$drawEveryBlock(BeltBlockEntity be, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }

    @Unique
    private static Model siftec$model(PartialModel partial, BlockState state) {
        if (state.getValue(BeltBlock.CASING)) return Models.chunkPartial(partial);
        BeltSlope slope = state.getValue(BeltBlock.SLOPE);
        if (slope != BeltSlope.HORIZONTAL && slope != BeltSlope.VERTICAL) return Models.chunkPartial(partial);
        boolean low = slope == BeltSlope.HORIZONTAL || modelBottomOnBack(state);
        return Models.chunkPartial(partial, low, (towardLow, ms) -> {
            if (towardLow) {
                ms.scale(1, LowBeltRender.SQUEEZE, 1);
                ms.translate(0, -LowBeltRender.BAND_FROM, 0);
            } else {
                ms.translate(0, 1, 0);
                ms.scale(1, LowBeltRender.SQUEEZE, 1);
                ms.translate(0, -LowBeltRender.BAND_TO, 0);
            }
        });
    }

    /** True if Create's rotation of this vertical belt's model (BeltVisual's own) turns the model's bottom to the back face. */
    @Unique
    private static boolean modelBottomOnBack(BlockState state) {
        Direction facing = state.getValue(BeltBlock.HORIZONTAL_FACING);
        boolean alongX = facing.getAxis() == Direction.Axis.X, alongZ = facing.getAxis() == Direction.Axis.Z;
        float rotX = 90 + (alongZ ? 180 : 0);
        float rotY = facing.toYRot() + (alongX ? 180 : 0) + (alongX ? 90 : 0);
        float rotZ = alongX ? 90 : 0;
        Quaternionf q = new Quaternionf().rotationXYZ(rotX * Mth.DEG_TO_RAD, rotY * Mth.DEG_TO_RAD, rotZ * Mth.DEG_TO_RAD);
        Vector3f down = q.transform(new Vector3f(0, -1, 0));
        Direction back = BeltShapesLow.backFace(state);
        return down.x * back.getStepX() + down.z * back.getStepZ() > 0.5f;
    }
}
