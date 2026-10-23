package com.meakaandre.siftec.client;

import com.meakaandre.siftec.drone.DroneEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.zurrtum.create.AllBlocks;
import com.zurrtum.create.client.AllPartialModels;
import com.zurrtum.create.client.catnip.render.CachedBuffers;
import com.zurrtum.create.client.catnip.render.SuperByteBuffer;
import com.zurrtum.create.client.catnip.render.SuperByteBufferRenderState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * The Cardboard Drone, from Create's own models and textures only: a cardboard body (Create's cardboard block) on a
 * brass frame (brass casing arms crossed over it, a motor pod at each end and two skids under it), four Encased Fan
 * propellers turning on the pods, and a Create package slung under it while it carries one. The whole leans as it flies
 * ({@link DroneEntity}'s pitch and roll) and turns to its heading.
 */
public class DroneRenderer extends EntityRenderer<DroneEntity, DroneRenderer.State> {
    /** How far out the motor pods sit from the middle, along each diagonal. */
    private static final float ARM = 0.55f;
    /** How much larger than its parts' sizes it is drawn: about two blocks across, half the pad's touchdown square. */
    private static final float SIZE = 1.6f;
    /** Height in the frame the drone leans about (just over its skids). */
    private static final float LEAN_PIVOT = 0.25f;
    /** Scale of a propeller's fan model. */
    private static final float PROPELLER_SCALE = 0.62f;

    public DroneRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0.5f;
    }

    public static class State extends EntityRenderState {
        public final List<SuperByteBufferRenderState> parts = new ArrayList<>();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(DroneEntity drone, State state, float partialTick) {
        super.extractRenderState(drone, state, partialTick);
        state.parts.clear();
        float yaw = drone.getYRot(partialTick);
        float pitch = Mth.lerp(partialTick, drone.pitchO, drone.pitch), roll = Mth.lerp(partialTick, drone.rollO, drone.roll);
        float spin = Mth.lerp(partialTick, drone.spinO, drone.spin);
        Parts p = new Parts(state.parts, yaw, pitch, roll, state.lightCoords);
        BlockState cardboard = AllBlocks.CARDBOARD_BLOCK.defaultBlockState(), brass = AllBlocks.BRASS_CASING.defaultBlockState();
        // the body, and the package it carries under it
        p.block(cardboard, 0, 0.12f, 0, 0.55f, 0.3f, 0.65f, 0);
        if (drone.loaded()) p.block(cardboard, 0, -0.18f, 0, 0.4f, 0.28f, 0.4f, 0);
        // the brass frame: two arms crossed over the body, a pod at each end, two skids
        p.block(brass, 0, 0.3f, 0, ARM * 2.9f, 0.07f, 0.09f, 45);
        p.block(brass, 0, 0.3f, 0, ARM * 2.9f, 0.07f, 0.09f, -45);
        for (int i = 0; i < 4; i++) {
            float x = (i & 1) == 0 ? -ARM : ARM, z = (i & 2) == 0 ? -ARM : ARM;
            p.block(brass, x, 0.26f, z, 0.16f, 0.14f, 0.16f, 0);
            // propellers: alternate ones turn the other way, as a quadcopter's do
            p.propeller(x, 0.4f, z, (i == 0 || i == 3) ? spin : -spin);
        }
        for (int side = -1; side <= 1; side += 2) {
            p.block(brass, side * 0.24f, -0.03f, 0, 0.05f, 0.05f, 0.7f, 0);
            p.block(brass, side * 0.24f, 0.0f, 0.2f, 0.05f, 0.14f, 0.05f, 0);
            p.block(brass, side * 0.24f, 0.0f, -0.2f, 0.05f, 0.14f, 0.05f, 0);
        }
    }

    @Override
    public void submit(State state, PoseStack ms, SubmitNodeCollector queue, CameraRenderState camera) {
        for (SuperByteBufferRenderState part : state.parts) part.submit(ms, queue);
        super.submit(state, ms, queue, camera);
    }

    /** Puts parts into the render state, in the drone's frame (x right, y up, z forward), leaned and turned with it. */
    private record Parts(List<SuperByteBufferRenderState> out, float yaw, float pitch, float roll, int light) {
        private SuperByteBuffer frame(SuperByteBuffer b) {
            // turned to its heading, then leaned about its middle (just over its skids)
            return b.rotateYDegrees(-yaw).scale(SIZE).translate(0, LEAN_PIVOT, 0).rotateXDegrees(pitch).rotateZDegrees(-roll).translate(0, -LEAN_PIVOT, 0);
        }

        /** A block's model, its middle at (x, y, z) of the frame, scaled to sx by sy by sz and turned by yawDegrees about y. */
        void block(BlockState state, float x, float y, float z, float sx, float sy, float sz, float yawDegrees) {
            SuperByteBuffer b = frame(CachedBuffers.block(state)).translate(-x, y, z).rotateYDegrees(yawDegrees).scale(sx, sy, sz).translate(-0.5f, -0.5f, -0.5f);
            out.add(b.light(light).extractRenderState());
        }

        /** An Encased Fan's propeller lying flat, its middle at (x, y, z), turned by degrees. */
        void propeller(float x, float y, float z, float degrees) {
            BlockState fan = AllBlocks.ENCASED_FAN.defaultBlockState();
            SuperByteBuffer b = frame(CachedBuffers.partialFacing(AllPartialModels.ENCASED_FAN_INNER, fan, Direction.UP))
                .translate(-x, y, z).rotateYDegrees(degrees).scale(PROPELLER_SCALE, PROPELLER_SCALE, PROPELLER_SCALE).translate(-0.5f, -0.5f, -0.5f);
            out.add(b.light(light).extractRenderState());
        }
    }
}
