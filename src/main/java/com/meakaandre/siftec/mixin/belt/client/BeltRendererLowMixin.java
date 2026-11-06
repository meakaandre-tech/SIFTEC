package com.meakaandre.siftec.mixin.belt.client;

import com.meakaandre.siftec.client.belt.LowBeltRender;
import com.mojang.blaze3d.vertex.PoseStack;
import com.zurrtum.create.client.content.kinetics.belt.BeltRenderer;
import com.zurrtum.create.content.kinetics.belt.BeltBlock;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.content.kinetics.belt.BeltSlope;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Create's belt renderer draws the one-block belt (see {@link LowBeltRender}): every belt block is drawn (its trim, caps
 * and built-in attachment), Create's band is squeezed to the bottom of the block, and items ride on the squeezed band
 * (up or down a vertical belt).
 */
@Mixin(value = BeltRenderer.class, remap = false)
public abstract class BeltRendererLowMixin {
    @Unique
    private static final double DRAW_DISTANCE = 64;
    @Unique
    private boolean siftec$squeezed;

    @Inject(method = "shouldRender(Lcom/zurrtum/create/content/kinetics/belt/BeltBlockEntity;Lnet/minecraft/world/phys/Vec3;)Z", at = @At("RETURN"), cancellable = true)
    private void siftec$everyBlock(BeltBlockEntity be, Vec3 camera, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() && Vec3.atCenterOf(be.getBlockPos()).closerThan(camera, DRAW_DISTANCE)) cir.setReturnValue(true);
    }

    @Inject(method = "extractRenderState(Lcom/zurrtum/create/content/kinetics/belt/BeltBlockEntity;Lcom/zurrtum/create/client/content/kinetics/belt/BeltRenderer$BeltRenderState;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
        at = @At("HEAD"))
    private void siftec$extractLow(BeltBlockEntity be, BeltRenderer.BeltRenderState state, float partialTicks, Vec3 camera,
                                   ModelFeatureRenderer.CrumblingOverlay crumbling, CallbackInfo ci) {
        ((LowBeltRender.State) state).siftec$low(LowBeltRender.extract(be, partialTicks, camera));
    }

    @Inject(method = "extractRenderState(Lcom/zurrtum/create/content/kinetics/belt/BeltBlockEntity;Lcom/zurrtum/create/client/content/kinetics/belt/BeltRenderer$BeltRenderState;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
        at = @At("RETURN"))
    private void siftec$itemsLow(BeltBlockEntity be, BeltRenderer.BeltRenderState state, float partialTicks, Vec3 camera,
                                 ModelFeatureRenderer.CrumblingOverlay crumbling, CallbackInfo ci) {
        if (state.items == null || state.beltStartOffset == null || state.blockState == null) return;
        Vec3 start = LowBeltRender.itemStart(state.blockState, state.beltStartOffset);
        if (start == null) return;
        state.beltStartOffset = start;
        if (state.blockState.getValue(BeltBlock.SLOPE) == BeltSlope.VERTICAL) {
            boolean up = state.blockState.getValue(BeltBlock.HORIZONTAL_FACING).getAxisDirection() == Direction.AxisDirection.POSITIVE;
            state.directionVec = new Vec3i(0, up ? 1 : -1, 0);
        }
    }

    @Inject(method = "submit(Lcom/zurrtum/create/client/content/kinetics/belt/BeltRenderer$BeltRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At("HEAD"))
    private void siftec$submitLow(BeltRenderer.BeltRenderState state, PoseStack ms, SubmitNodeCollector queue, CameraRenderState camera, CallbackInfo ci) {
        LowBeltRender.Parts parts = ((LowBeltRender.State) state).siftec$low();
        LowBeltRender.submit(parts, ms, queue, camera);
        siftec$squeezed = LowBeltRender.squeeze(parts, ms);
    }

    /** The band is drawn; the squeeze ends before the items (the first read of the items). */
    @Inject(method = "submit(Lcom/zurrtum/create/client/content/kinetics/belt/BeltRenderer$BeltRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At(value = "FIELD", target = "Lcom/zurrtum/create/client/content/kinetics/belt/BeltRenderer$BeltRenderState;items:[Lcom/zurrtum/create/client/content/kinetics/belt/BeltRenderer$BeltItemState;", ordinal = 0))
    private void siftec$unsqueeze(BeltRenderer.BeltRenderState state, PoseStack ms, SubmitNodeCollector queue, CameraRenderState camera, CallbackInfo ci) {
        if (siftec$squeezed) ms.popPose();
        siftec$squeezed = false;
    }
}
