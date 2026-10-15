package com.meakaandre.siftec.client;

import com.meakaandre.siftec.vehicle.VehicleEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.zurrtum.create.AllBlocks;
import com.zurrtum.create.client.AllPartialModels;
import com.zurrtum.create.client.AllSpriteShifts;
import com.zurrtum.create.client.catnip.render.CachedBuffers;
import com.zurrtum.create.client.catnip.render.SpriteShiftEntry;
import com.zurrtum.create.client.catnip.render.SuperByteBuffer;
import com.zurrtum.create.client.catnip.render.SuperByteBufferRenderState;
import com.zurrtum.create.client.flywheel.lib.model.baked.PartialModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a vehicle from Create's own models and textures only: casings for the body, a seat, fluid tanks and Item
 * Vaults, cogwheels for wheels (they turn as it rolls) and belts for the Truck's treads (they run as it rolls).
 * Each part is placed in the vehicle's frame (x across, y up, z forward, from the middle of its bottom) and the whole
 * is turned to its heading.
 */
public class VehicleRenderer extends EntityRenderer<VehicleEntity, VehicleRenderer.State> {
    public VehicleRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 1.2f;
    }

    public static class State extends EntityRenderState {
        public final List<SuperByteBufferRenderState> parts = new ArrayList<>();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(VehicleEntity vehicle, State state, float partialTick) {
        super.extractRenderState(vehicle, state, partialTick);
        state.parts.clear();
        Parts parts = new Parts(state.parts, vehicle.getYRot(partialTick), state.lightCoords, Mth.lerp(partialTick, vehicle.rollO, vehicle.roll));
        switch (vehicle.kind) {
            case TRACTOR -> tractor(parts);
            case TRUCK -> truck(parts);
            case EXPLORER -> explorer(parts);
        }
    }

    @Override
    public void submit(State state, PoseStack ms, SubmitNodeCollector queue, CameraRenderState camera) {
        for (SuperByteBufferRenderState part : state.parts) part.submit(ms, queue);
        super.submit(state, ms, queue, camera);
    }

    // ------------------------------------------------------------------------------------------------ the models

    private static BlockState casing(boolean brass) {
        return (brass ? AllBlocks.BRASS_CASING : AllBlocks.ANDESITE_CASING).defaultBlockState();
    }

    private static BlockState seat() {
        return AllBlocks.SEAT.pick(DyeColor.BLACK).defaultBlockState();
    }

    private static BlockState acrossX(BlockState state) {
        return state.setValue(BlockStateProperties.AXIS, Direction.Axis.X);
    }

    /** The Tractor: an andesite body with a fuel tank and hood, big cogwheels at the back and small ones in front. */
    private static void tractor(Parts p) {
        for (int z = -1; z <= 1; z++) p.block(casing(false), 0, 0.5, z);
        p.block(seat(), 0, 1.5, -1);
        p.block(AllBlocks.FLUID_TANK.defaultBlockState(), 0, 1.5, 0);
        p.block(casing(false), 0, 1.5, 1);
        p.block(AllBlocks.SHAFT.defaultBlockState(), 0.3, 2.5, 1.2);
        for (int side = -1; side <= 1; side += 2) {
            p.wheel(acrossX(AllBlocks.LARGE_COGWHEEL.defaultBlockState()), side * 1.0, 0.75, -1.0, 0.75);
            p.wheel(acrossX(AllBlocks.COGWHEEL.defaultBlockState()), side * 0.85, 0.5, 1.1, 0.5);
        }
    }

    /** The Truck: belt treads with turning axles under an andesite bed, a brass cab and Item Vaults as its cargo box. */
    private static void truck(Parts p) {
        for (int side = -1; side <= 1; side += 2) p.tread(side * 1.0, 0, -2, 2);
        for (int z = -2; z <= 2; z += 4) p.wheel(acrossX(AllBlocks.SHAFT.defaultBlockState()), 0, 0.5, z, 0.5);
        for (int x = -1; x <= 1; x++)
            for (int z = -2; z <= 2; z++) p.block(casing(false), x, 1, z);
        for (int x = -1; x <= 1; x++) p.block(casing(true), x, 2, 2);
        p.block(seat(), 0, 2, 1);
        p.block(AllBlocks.FLUID_TANK.defaultBlockState(), -1, 2, 1);
        p.block(casing(true), 1, 2, 1);
        BlockState vault = AllBlocks.ITEM_VAULT.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.Z);
        for (int x = -1; x <= 1; x++)
            for (int z = -2; z <= 0; z++) p.block(vault, x, 2, z);
    }

    /** The Explorer: a small brass buggy on four big cogwheels, quick and light. */
    private static void explorer(Parts p) {
        for (int z = -1; z <= 1; z++) p.block(casing(true), 0, 0.5, z);
        p.block(seat(), 0, 1.5, -0.5);
        p.block(AllBlocks.COPPER_CASING.defaultBlockState(), 0, 1.5, 0.6);
        for (int side = -1; side <= 1; side += 2)
            for (int z = -1; z <= 1; z += 2) p.wheel(acrossX(AllBlocks.LARGE_COGWHEEL.defaultBlockState()), side * 0.85, 0.75, z, 0.75);
    }

    /** Puts parts into the render state: where the vehicle stands, its heading, its light and how far it has rolled. */
    private record Parts(List<SuperByteBufferRenderState> out, float yaw, int light, float roll) {
        /** The vehicle's frame: turned to its heading; the model's x runs to the vehicle's right. */
        private SuperByteBuffer at(SuperByteBuffer b, double x, double y, double z) {
            return b.rotateYDegrees(-yaw).translate(-x - 0.5, y, z - 0.5);
        }

        /** A whole block's model with its bottom middle at (x, y, z). */
        void block(BlockState state, double x, double y, double z) {
            out.add(at(CachedBuffers.block(state), x, y, z).light(light).extractRenderState());
        }

        /** A wheel (or axle) across the vehicle, its middle at (x, y, z), turning as it rolls on its radius. */
        void wheel(BlockState state, double x, double y, double z, double radius) {
            float degrees = (float) (roll / radius * Mth.RAD_TO_DEG);
            out.add(at(CachedBuffers.block(state), x, y - 0.5, z).center().rotateXDegrees(degrees).uncenter().light(light).extractRenderState());
        }

        /** A tread: a Create belt along the vehicle from z0 to z1, its texture running with the ground. */
        void tread(double x, double y, int z0, int z1) {
            for (int z = z0; z <= z1; z++) {
                boolean start = z == z0, end = z == z1;
                PartialModel top = start ? AllPartialModels.BELT_START : end ? AllPartialModels.BELT_END : AllPartialModels.BELT_MIDDLE;
                PartialModel bottom = start ? AllPartialModels.BELT_START_BOTTOM : end ? AllPartialModels.BELT_END_BOTTOM : AllPartialModels.BELT_MIDDLE_BOTTOM;
                out.add(scrolled(at(CachedBuffers.partial(top, AllBlocks.BELT.defaultBlockState()), x, y, z), AllSpriteShifts.BELT, roll * 0.5f)
                    .light(light).extractRenderState());
                out.add(scrolled(at(CachedBuffers.partial(bottom, AllBlocks.BELT.defaultBlockState()), x, y, z), AllSpriteShifts.BELT_OFFSET, roll * 0.5f + 0.5f)
                    .light(light).extractRenderState());
            }
        }

        /** Shifts a belt's texture as Create's belts do, by how far it has run (a whole texture every two blocks). */
        private static SuperByteBuffer scrolled(SuperByteBuffer b, SpriteShiftEntry shift, float scroll) {
            TextureAtlasSprite target = shift.getTarget();
            float size = target.getV1() - target.getV0();
            return b.shiftUVScrolling(shift, (float) ((scroll - Math.floor(scroll)) * size * 0.5f));
        }
    }
}
