package com.meakaandre.siftec.client;

import com.meakaandre.siftec.block.MinerBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.zurrtum.create.client.AllPartialModels;
import com.zurrtum.create.client.catnip.render.CachedBuffers;
import com.zurrtum.create.client.catnip.render.SuperByteBufferRenderState;
import com.zurrtum.create.client.foundation.blockEntity.renderer.SmartBlockEntityRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.UnknownNullability;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import static com.zurrtum.create.client.content.kinetics.base.KineticBlockEntityRenderer.getRotateAngleWithoutBeOffset;
import static com.zurrtum.create.client.content.kinetics.base.KineticBlockEntityRenderer.getTintColor;

/**
 * The turning parts of a miner, as on the old node drills: Create's drill head pointing down with its tip resting on
 * the node (a size bigger than Create's), a cog like the Mechanical Mixer's on top of the casing with its teeth
 * sticking out of the sides, and the shaft end above it in the upper block. All three turn with the shaft.
 */
public class MinerRenderer implements BlockEntityRenderer<MinerBlockEntity, MinerRenderer.State> {
    public MinerRenderer(Context context) {
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(MinerBlockEntity be, State state, float partialTicks, Vec3 camera, @Nullable CrumblingOverlay breakProgress) {
        Level level = SmartBlockEntityRenderer.extractBase(be, state, breakProgress);
        CardinalLighting lighting = SmartBlockEntityRenderer.getCardinalLighting(level);
        int color = getTintColor(be);
        net.minecraft.world.level.block.state.BlockState blockState = be.getBlockState();
        int light = SmartBlockEntityRenderer.getLightCoords(level, be.getBlockPos());
        // the cog and shaft end are up in the upper block: lit as it is
        int lightAbove = SmartBlockEntityRenderer.getLightCoords(level, be.getBlockPos().above());
        state.head = CachedBuffers.partialFacing(AllPartialModels.DRILL_HEAD, blockState, Direction.DOWN)
            .cardinalLighting(lighting).light(light).color(color).extractRenderState();
        state.cog = CachedBuffers.partial(AllPartialModels.SHAFTLESS_COGWHEEL, blockState)
            .cardinalLighting(lighting).light(lightAbove).color(color).extractRenderState();
        state.shaft = CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, blockState, Direction.UP)
            .cardinalLighting(lighting).light(lightAbove).color(color).extractRenderState();
        state.angle = getRotateAngleWithoutBeOffset(Direction.Axis.Y, be, state, level);
    }

    @Override
    public void submit(State state, PoseStack matrices, SubmitNodeCollector queue, CameraRenderState camera) {
        // the bit: 1.2 times Create's drill head, hanging from the casing with its tip just into the top of the node
        // (Create's head points 4 px out of its block; here its tip goes to -1 px)
        matrices.pushPose();
        spin(state, matrices);
        matrices.translate(0.5f, -1 / 16f, 0.5f);
        matrices.scale(1.2f, 1.2f, 1.2f);
        matrices.translate(-0.5f, 0.25f, -0.5f);
        state.head.submit(matrices, queue);
        matrices.popPose();
        // the cog on top of the casing (its middle at 29 px), a little wider than a plain cog
        matrices.pushPose();
        matrices.translate(0, 21 / 16f, 0);
        spin(state, matrices);
        matrices.translate(0.5f, 0.5f, 0.5f);
        matrices.scale(1.15f, 1f, 1.15f);
        matrices.translate(-0.5f, -0.5f, -0.5f);
        state.cog.submit(matrices, queue);
        matrices.popPose();
        // the shaft end in the upper block, joining a shaft above
        matrices.pushPose();
        matrices.translate(0, 1, 0);
        spin(state, matrices);
        state.shaft.submit(matrices, queue);
        matrices.popPose();
    }

    private static void spin(State state, PoseStack matrices) {
        if (state.angle != null) matrices.rotateAround(state.angle, 0.5f, 0.5f, 0.5f);
    }

    /** The miner reaches into the block above and down onto the node: draw it even when its own block is off screen. */
    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    public static class State extends BlockEntityRenderState {
        public @UnknownNullability SuperByteBufferRenderState head;
        public @UnknownNullability SuperByteBufferRenderState cog;
        public @UnknownNullability SuperByteBufferRenderState shaft;
        public @Nullable Quaternionf angle;
    }
}
