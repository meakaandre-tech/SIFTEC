package com.meakaandre.siftec.client.belt;

import com.meakaandre.siftec.belt.BeltAttachments;
import com.meakaandre.siftec.belt.BeltLook;
import com.meakaandre.siftec.belt.BeltShapesLow;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zurrtum.create.AllBlocks;
import com.zurrtum.create.client.catnip.render.CachedBuffers;
import com.zurrtum.create.client.catnip.render.SuperByteBufferRenderState;
import com.zurrtum.create.content.kinetics.belt.BeltBlock;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.content.kinetics.belt.BeltPart;
import com.zurrtum.create.content.kinetics.belt.BeltSlope;
import com.zurrtum.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Drawing the one-block belt, added to Create's belt renderer: Create's band squeezed to the bottom 4 px of its block
 * (to the back 4 px for a vertical belt), the side trim in the belt Mk's material, the rounded end caps, and a built-in
 * funnel or tunnel drawn in the belt's block (its block model and Create's own renderer, flaps and filter and all).
 */
public final class LowBeltRender {
    /** Create's band runs from 3 to 13 px up its model; it is squeezed to 0 to 4 px. */
    public static final float BAND_FROM = 3 / 16f, BAND_TO = 13 / 16f, SQUEEZE = (BeltShapesLow.BAND_PX / 16f) / (BAND_TO - BAND_FROM);

    /** What the mixin adds to Create's belt render state. */
    public interface State {
        @Nullable Parts siftec$low();

        void siftec$low(@Nullable Parts parts);
    }

    /** One belt block's extra parts, gathered on extract and drawn on submit. */
    public static final class Parts {
        BeltSlope slope = BeltSlope.HORIZONTAL;
        Direction facing = Direction.SOUTH;
        boolean squeeze;
        @Nullable SuperByteBufferRenderState rails, cap;
        /** The cap is drawn turned round: at the far end of a flat belt, at the top of a vertical one. */
        boolean capTurned;
        @Nullable SuperByteBufferRenderState block;
        @Nullable BlockEntityRenderer<BlockEntity, BlockEntityRenderState> renderer;
        @Nullable BlockEntityRenderState inner;
    }

    private LowBeltRender() {
    }

    /** Gathers the belt block's extra parts (every belt block is drawn, not only the controller). */
    @SuppressWarnings("unchecked")
    public static @Nullable Parts extract(BeltBlockEntity be, float partialTicks, Vec3 camera) {
        Level level = be.getLevel();
        BlockState state = be.getBlockState();
        if (level == null || !state.is(AllBlocks.BELT) || state.getValue(BeltBlock.CASING)) return null;
        BeltSlope slope = state.getValue(BeltBlock.SLOPE);
        if (slope == BeltSlope.SIDEWAYS) return null;
        Parts p = new Parts();
        p.slope = slope;
        p.facing = state.getValue(BeltBlock.HORIZONTAL_FACING);
        int light = LightCoordsUtil.getLightCoords(level, be.getBlockPos());
        boolean flat = slope == BeltSlope.HORIZONTAL, vertical = slope == BeltSlope.VERTICAL;
        p.squeeze = flat || vertical;
        if (flat || vertical) {
            BeltBlockEntity controller = be.isController() ? be : be.getControllerBE();
            int mk = controller == null ? 1 : BeltLook.mk(controller);
            p.rails = CachedBuffers.partial(LowBeltPartials.rail(BeltLook.trim(mk)), state).light(light).extractRenderState();
            BeltPart part = state.getValue(BeltBlock.PART);
            if (part == BeltPart.START || part == BeltPart.END) {
                p.cap = CachedBuffers.partial(LowBeltPartials.CAP, state).light(light).extractRenderState();
                boolean up = p.facing.getAxisDirection() == Direction.AxisDirection.POSITIVE;
                // a flat belt's start is its back end; a vertical one's start is its foot when it runs up
                p.capTurned = vertical ? (part == BeltPart.START) != up : part == BeltPart.END;
            }
        }
        SmartBlockEntity a = BeltAttachments.of(be);
        if (a != null) {
            p.block = CachedBuffers.block(a.getBlockState()).light(light).extractRenderState();
            BlockEntityRenderer<BlockEntity, BlockEntityRenderState> renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(a);
            if (renderer != null) {
                BlockEntityRenderState inner = renderer.createRenderState();
                BeltAttachments.run(() -> renderer.extractRenderState(a, inner, partialTicks, camera, null));
                inner.lightCoords = light;
                p.renderer = renderer;
                p.inner = inner;
            }
        }
        return p;
    }

    /** Draws the extra parts; then {@link #squeeze} for Create's band. */
    public static void submit(@Nullable Parts p, PoseStack ms, SubmitNodeCollector queue, CameraRenderState camera) {
        if (p == null) return;
        if (p.rails != null) {
            ms.pushPose();
            orient(p, ms, false);
            p.rails.submit(ms, queue);
            ms.popPose();
        }
        if (p.cap != null) {
            ms.pushPose();
            orient(p, ms, p.capTurned);
            p.cap.submit(ms, queue);
            ms.popPose();
        }
        if (p.block != null) p.block.submit(ms, queue);
        if (p.renderer != null && p.inner != null) p.renderer.submit(p.inner, ms, queue, camera);
    }

    /**
     * Turns the trim's model (made running south along a flat belt, rails 5 px up from the floor) to the belt: round to
     * its facing for a flat belt, or stood up against its back face for a vertical one.
     */
    private static void orient(Parts p, PoseStack ms, boolean turned) {
        boolean vertical = p.slope == BeltSlope.VERTICAL;
        Direction toward = vertical ? BeltShapesLow.backFace(stateOf(p)) : p.facing;
        rotateY(ms, angle(toward));
        if (vertical) {
            // model z runs up the belt, model height out from the back face
            ms.translate(0, 0, 1);
            ms.rotate(Axis.XP.rotationDegrees(-90));
        }
        if (turned) rotateY(ms, 180);
    }

    private static BlockState stateOf(Parts p) {
        return AllBlocks.BELT.defaultBlockState().setValue(BeltBlock.HORIZONTAL_FACING, p.facing).setValue(BeltBlock.SLOPE, p.slope);
    }

    /** Degrees about Y that turn the south-facing model to face this way. */
    private static float angle(Direction d) {
        return switch (d) {
            case EAST -> 90;
            case NORTH -> 180;
            case WEST -> 270;
            default -> 0;
        };
    }

    private static void rotateY(PoseStack ms, float degrees) {
        if (degrees == 0) return;
        ms.translate(0.5f, 0, 0.5f);
        ms.rotate(Axis.YP.rotationDegrees(degrees));
        ms.translate(-0.5f, 0, -0.5f);
    }

    /**
     * Squeezes what is drawn next (Create's band, in the block's own frame) into the bottom 4 px of a flat belt's block, or
     * the 4 px against a vertical belt's back face; true if the pose was pushed (pop it after the band).
     */
    public static boolean squeeze(@Nullable Parts p, PoseStack ms) {
        if (p == null || !p.squeeze) return false;
        ms.pushPose();
        if (p.slope == BeltSlope.HORIZONTAL) {
            ms.scale(1, SQUEEZE, 1);
            ms.translate(0, -BAND_FROM, 0);
            return true;
        }
        Direction back = BeltShapesLow.backFace(stateOf(p));
        Direction.Axis axis = back.getAxis();
        boolean far = back.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        float sx = axis == Direction.Axis.X ? SQUEEZE : 1, sz = axis == Direction.Axis.Z ? SQUEEZE : 1;
        float tx = axis == Direction.Axis.X ? 1 : 0, tz = axis == Direction.Axis.Z ? 1 : 0;
        if (far) {
            // c' = 1 - (BAND_TO - c) * squeeze
            ms.translate(tx, 0, tz);
            ms.scale(sx, 1, sz);
            ms.translate(-BAND_TO * tx, 0, -BAND_TO * tz);
        } else {
            ms.scale(sx, 1, sz);
            ms.translate(-BAND_FROM * tx, 0, -BAND_FROM * tz);
        }
        return true;
    }

    /** Items ride on the squeezed band: where Create's item start offset goes for this belt (null: leave Create's). */
    public static @Nullable Vec3 itemStart(BlockState state, Vec3 create) {
        if (!state.is(AllBlocks.BELT) || state.getValue(BeltBlock.CASING)) return null;
        BeltSlope slope = state.getValue(BeltBlock.SLOPE);
        if (slope == BeltSlope.HORIZONTAL) return new Vec3(create.x, BeltShapesLow.BAND_PX / 16.0 + 2 / 16.0, create.z);
        if (slope != BeltSlope.VERTICAL) return null;
        Direction back = BeltShapesLow.backFace(state);
        boolean up = state.getValue(BeltBlock.HORIZONTAL_FACING).getAxisDirection() == Direction.AxisDirection.POSITIVE;
        double out = 0.5 - (BeltShapesLow.BAND_PX + 2) / 16.0;
        return new Vec3(0.5 + back.getStepX() * out, up ? 0 : 1, 0.5 + back.getStepZ() * out);
    }
}
