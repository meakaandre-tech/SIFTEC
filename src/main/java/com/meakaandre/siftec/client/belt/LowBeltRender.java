package com.meakaandre.siftec.client.belt;

import com.meakaandre.siftec.belt.BeltAttachments;
import com.meakaandre.siftec.belt.BeltLook;
import com.meakaandre.siftec.belt.BeltShapesLow;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zurrtum.create.AllBlocks;
import com.zurrtum.create.client.catnip.render.CachedBuffers;
import com.zurrtum.create.client.catnip.render.SuperByteBufferRenderState;
import com.zurrtum.create.client.flywheel.lib.model.baked.PartialModel;
import com.zurrtum.create.content.kinetics.belt.BeltBlock;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.content.kinetics.belt.BeltHelper;
import com.zurrtum.create.content.kinetics.belt.BeltPart;
import com.zurrtum.create.content.kinetics.belt.BeltSlope;
import com.zurrtum.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Drawing the one-block belt, added to Create's belt renderer: Create's band squeezed to the bottom 4 px of its block
 * (to the back 4 px for a vertical belt), the side trim in the belt Mk's material, the rounded end caps, and a built-in
 * funnel or tunnel drawn in the belt's block (its block model and Create's own renderer, flaps and filter and all). A
 * sloped belt keeps Create's band as it is; its trim and caps follow Create's diagonal pieces, inside each cell.
 */
public final class LowBeltRender {
    /** Create's band runs from 3 to 13 px up its model; it is squeezed to 0 to 4 px. */
    public static final float BAND_FROM = 3 / 16f, BAND_TO = 13 / 16f, SQUEEZE = (BeltShapesLow.BAND_PX / 16f) / (BAND_TO - BAND_FROM);

    /** What the mixin adds to Create's belt render state. */
    public interface State {
        @Nullable Parts siftec$low();

        void siftec$low(@Nullable Parts parts);
    }

    /**
     * One belt block's render additions, gathered on extract and drawn on submit: how its own band is squeezed, and, on
     * the belt's controller, the trim, caps and built-in attachments of every block of the belt. The controller draws
     * them all (as Create draws all of a belt's items from it): with Flywheel, Create's renderer only runs for a belt's
     * controller, so a block that drew its own would show its bare band, with the ground under it showing through
     * beside it.
     */
    public static final class Parts {
        BeltSlope slope = BeltSlope.HORIZONTAL;
        Direction facing = Direction.SOUTH;
        boolean squeeze;
        final List<Decor> decor = new ArrayList<>();

        /** True if this block draws trim, caps or attachments (a controller's, for every block of its belt). */
        public boolean draws() {
            return !decor.isEmpty();
        }
    }

    /** One belt block's trim, cap and built-in attachment, at its offset from the controller. */
    static final class Decor {
        BlockPos offset = BlockPos.ZERO;
        BeltSlope slope = BeltSlope.HORIZONTAL;
        Direction facing = Direction.SOUTH;
        @Nullable SuperByteBufferRenderState rails, cap;
        /** What the rails and cap are, for {@link #look}. */
        @Nullable PartialModel railsModel, capModel;
        BeltLook.@Nullable Trim trim;
        /** The cap is drawn turned round: at the far end of a flat belt, at the top of a vertical one. */
        boolean capTurned;
        @Nullable SuperByteBufferRenderState block;
        @Nullable BlockEntityRenderer<BlockEntity, BlockEntityRenderState> renderer;
        @Nullable BlockEntityRenderState inner;
    }

    /** What one belt block draws besides Create's band (for the selftests): its trim, whether it has a cap, and the box all of it fills, in the block's own space. */
    public record Look(@Nullable String trim, boolean cap, float[] min, float[] max, @Nullable String attachment) {
    }

    private LowBeltRender() {
    }

    /** Gathers a belt block's render additions (see {@link Parts}); null for a block that draws nothing of its own. */
    public static @Nullable Parts extract(BeltBlockEntity be, float partialTicks, Vec3 camera) {
        Level level = be.getLevel();
        if (level == null) return null;
        BlockState state = PonderBuiltIn.drawn(level, be.getBlockPos(), be.getBlockState());
        Parts p = new Parts();
        if (state.is(AllBlocks.BELT) && !state.getValue(BeltBlock.CASING)) {
            p.slope = state.getValue(BeltBlock.SLOPE);
            p.facing = state.getValue(BeltBlock.HORIZONTAL_FACING);
            p.squeeze = p.slope == BeltSlope.HORIZONTAL || p.slope == BeltSlope.VERTICAL;
        }
        if (be.isController()) {
            int mk = BeltLook.mk(be);
            for (int i = 0; i < Math.max(1, be.beltLength); i++) {
                BlockPos pos = be.beltLength == 0 ? be.getBlockPos() : BeltHelper.getPositionForOffset(be, i);
                if (!(level.getBlockEntity(pos) instanceof BeltBlockEntity segment)) continue;
                Decor d = decor(segment, level, mk, partialTicks, camera);
                if (d == null) continue;
                d.offset = pos.subtract(be.getBlockPos());
                p.decor.add(d);
            }
        }
        return p.squeeze || !p.decor.isEmpty() ? p : null;
    }

    /** One block's trim, cap and built-in attachment, or null for a block with none (encased or sideways). */
    @SuppressWarnings("unchecked")
    private static @Nullable Decor decor(BeltBlockEntity be, Level level, int mk, float partialTicks, Vec3 camera) {
        BlockState state = PonderBuiltIn.drawn(level, be.getBlockPos(), be.getBlockState());
        if (!state.is(AllBlocks.BELT) || state.getValue(BeltBlock.CASING)) return null;
        BeltSlope slope = state.getValue(BeltBlock.SLOPE);
        if (slope == BeltSlope.SIDEWAYS) return null;
        Decor p = new Decor();
        p.slope = slope;
        p.facing = state.getValue(BeltBlock.HORIZONTAL_FACING);
        int light = LightCoordsUtil.getLightCoords(level, be.getBlockPos());
        boolean flat = slope == BeltSlope.HORIZONTAL, vertical = slope == BeltSlope.VERTICAL;
        p.trim = BeltLook.trim(mk);
        BeltPart part = state.getValue(BeltBlock.PART);
        boolean end = part == BeltPart.START || part == BeltPart.END;
        if (flat || vertical) {
            p.railsModel = LowBeltPartials.rail(p.trim);
            if (end) {
                p.capModel = LowBeltPartials.CAP;
                boolean up = p.facing.getAxisDirection() == Direction.AxisDirection.POSITIVE;
                // a flat belt's start is its back end; a vertical one's start is its foot when it runs up
                p.capTurned = vertical ? (part == BeltPart.START) != up : part == BeltPart.END;
            }
        } else {
            // a sloped belt: the trim of Create's diagonal piece for this block, and a cap where its end piece wraps round
            p.railsModel = LowBeltPartials.slope(p.trim, part);
            if (end) p.capModel = LowBeltPartials.slopeCap(part);
        }
        p.rails = CachedBuffers.partial(p.railsModel, state).light(light).extractRenderState();
        if (p.capModel != null) p.cap = CachedBuffers.partial(p.capModel, state).light(light).extractRenderState();
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

    /** Draws every block's trim, cap and attachment (on the controller); then {@link #squeeze} for this block's band. */
    public static void submit(@Nullable Parts parts, PoseStack ms, SubmitNodeCollector queue, CameraRenderState camera) {
        if (parts == null) return;
        for (Decor p : parts.decor) {
            ms.pushPose();
            ms.translate(p.offset.getX(), p.offset.getY(), p.offset.getZ());
            if (p.rails != null) {
                ms.pushPose();
                orient(p.slope, p.facing, ms, false);
                p.rails.submit(ms, queue);
                ms.popPose();
            }
            if (p.cap != null) {
                ms.pushPose();
                orient(p.slope, p.facing, ms, p.capTurned);
                p.cap.submit(ms, queue);
                ms.popPose();
            }
            if (p.block != null) p.block.submit(ms, queue);
            if (p.renderer != null && p.inner != null) p.renderer.submit(p.inner, ms, queue, camera);
            ms.popPose();
        }
    }

    /**
     * Turns the trim's model to the belt: a flat belt's (made running south, rails 5 px up from the floor) round to its
     * facing, a vertical one's stood up against its back face; a sloped belt's exactly as Create turns its diagonal piece.
     */
    private static void orient(BeltSlope slope, Direction facing, PoseStack ms, boolean turned) {
        if (slope.isDiagonal()) {
            ms.translate(0.5f, 0.5f, 0.5f);
            ms.rotate(diagonal(facing, slope));
            ms.translate(-0.5f, -0.5f, -0.5f);
            return;
        }
        boolean vertical = slope == BeltSlope.VERTICAL;
        Direction toward = vertical ? BeltShapesLow.backFace(stateOf(facing, slope)) : facing;
        rotateY(ms, angle(toward));
        if (vertical) {
            // model z runs up the belt, model height out from the back face
            ms.translate(0, 0, 1);
            ms.rotate(Axis.XP.rotationDegrees(-90));
        }
        if (turned) rotateY(ms, 180);
    }

    /** Create's turn of a diagonal belt piece (BeltVisual's and the belt renderer's), about the block's middle. */
    static Quaternionf diagonal(Direction facing, BeltSlope slope) {
        boolean downward = slope == BeltSlope.DOWNWARD, alongZ = facing.getAxis() == Direction.Axis.Z;
        float rotX = downward ? 180 : 0;
        float rotY = facing.toYRot() + (alongZ && !downward ? 180 : 0);
        return new Quaternionf().rotationXYZ(rotX * Mth.DEG_TO_RAD, rotY * Mth.DEG_TO_RAD, 0);
    }

    private static BlockState stateOf(Direction facing, BeltSlope slope) {
        return AllBlocks.BELT.defaultBlockState().setValue(BeltBlock.HORIZONTAL_FACING, facing).setValue(BeltBlock.SLOPE, slope);
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
        Direction back = BeltShapesLow.backFace(stateOf(p.facing, p.slope));
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

    // ------------------------------------------------------------------------------------------------ for the selftests

    /**
     * What this belt block draws besides Create's band, as it is drawn: the trim's material, whether it has an end cap,
     * and the box every vertex of its trim and cap fills in the block's own space (0 to 1 is inside the block), and the built-in funnel or tunnel it draws. Null for
     * a belt block that draws nothing of its own (encased, sideways).
     */
    public static @Nullable Look look(BeltBlockEntity be) {
        BeltBlockEntity controller = be.isController() ? be : be.getControllerBE();
        if (controller == null) return null;
        Parts parts = extract(controller, 1, Vec3.ZERO);
        if (parts == null) return null;
        BlockPos offset = be.getBlockPos().subtract(controller.getBlockPos());
        for (Decor p : parts.decor) {
            if (!p.offset.equals(offset)) continue;
            float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            bounds(p, p.railsModel, false, min, max);
            bounds(p, p.capModel, p.capTurned, min, max);
            SmartBlockEntity a = BeltAttachments.of(be);
            String attachment = p.block == null || a == null ? null : BuiltInRegistries.BLOCK.getKey(a.getBlockState().getBlock()).toString();
            return new Look(p.trim == null ? null : p.trim.name(), p.capModel != null, min, max, attachment);
        }
        return null;
    }

    private static void bounds(Decor p, @Nullable PartialModel model, boolean turned, float[] min, float[] max) {
        if (model == null || model.get() == null) return;
        PoseStack ms = new PoseStack();
        orient(p.slope, p.facing, ms, turned);
        Matrix4f pose = ms.last().pose();
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.get().collectParts(RandomSource.create(42L), parts);
        Vector3f v = new Vector3f();
        for (BlockStateModelPart part : parts) {
            List<BakedQuad> quads = new ArrayList<>(part.getQuads(null));
            for (Direction d : Direction.values()) quads.addAll(part.getQuads(d));
            for (BakedQuad q : quads)
                for (int i = 0; i < 4; i++) {
                    pose.transformPosition(v.set(q.position(i)));
                    min[0] = Math.min(min[0], v.x);
                    min[1] = Math.min(min[1], v.y);
                    min[2] = Math.min(min[2], v.z);
                    max[0] = Math.max(max[0], v.x);
                    max[1] = Math.max(max[1], v.y);
                    max[2] = Math.max(max[2], v.z);
                }
        }
    }
}
