package com.meakaandre.siftec.mixin.belt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.meakaandre.siftec.belt.BeltAttachments;
import com.meakaandre.siftec.belt.BeltShapesLow;
import com.zurrtum.create.AllItems;
import com.zurrtum.create.content.kinetics.belt.BeltBlock;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.content.kinetics.belt.BeltSlope;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The one-block belt: a funnel or tunnel clips into the belt block, the wrench reaches it, the belt's neighbour updates
 * (Create's tunnel connections) run in the stacked view, the belt keeps to the bottom of its block, and vertical belts
 * carry items.
 */
@Mixin(value = BeltBlock.class, remap = false)
public abstract class BeltBlockLowMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void siftec$clipIn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit,
                               CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult placed = BeltAttachments.place(stack, level, pos, player, hand, hit);
        if (placed != null) {
            cir.setReturnValue(placed);
            return;
        }
        // the wrench on a belt block with an attachment works the attachment (the wrench item's mixin), not Create's belt slicer
        if (stack.is(AllItems.WRENCH) && level.getBlockEntity(pos) instanceof BeltBlockEntity belt && BeltAttachments.of(belt) != null) {
            cir.setReturnValue(InteractionResult.TRY_WITH_EMPTY_HAND);
        }
    }

    @WrapMethod(method = "updateShape")
    private BlockState siftec$updateInView(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction side,
                                           BlockPos neighbourPos, BlockState neighbour, RandomSource random, Operation<BlockState> original) {
        return BeltAttachments.call(() -> original.call(state, level, ticks, pos, side, neighbourPos, neighbour, random));
    }

    @Inject(method = "canTransportObjects", at = @At("HEAD"), cancellable = true)
    private static void siftec$verticalCarries(BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (state.hasProperty(BeltBlock.SLOPE) && state.getValue(BeltBlock.SLOPE) == BeltSlope.VERTICAL) cir.setReturnValue(true);
    }

    /** A vertical belt carries items, not entities (nothing climbs it). */
    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void siftec$noClimbing(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effects, boolean bl, CallbackInfo ci) {
        if (state.getValue(BeltBlock.SLOPE) == BeltSlope.VERTICAL && !(entity instanceof ItemEntity)) ci.cancel();
    }

    @Inject(method = "getShape", at = @At("RETURN"), cancellable = true)
    private void siftec$lowShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        VoxelShape shape = BeltShapesLow.shape(state, cir.getReturnValue());
        BlockState attachment = level.getBlockEntity(pos) instanceof BeltBlockEntity belt ? BeltAttachments.stateOf(belt) : null;
        if (attachment != null) shape = Shapes.or(shape, attachment.getShape(level, pos, context));
        cir.setReturnValue(shape);
    }

    @Inject(method = "getCollisionShape", at = @At("RETURN"), cancellable = true)
    private void siftec$lowCollision(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (!state.hasProperty(BeltBlock.SLOPE)) return;
        VoxelShape low = BeltShapesLow.shape(state, cir.getReturnValue());
        if (low == cir.getReturnValue()) return;
        BlockState attachment = level.getBlockEntity(pos) instanceof BeltBlockEntity belt ? BeltAttachments.stateOf(belt) : null;
        if (attachment != null) low = Shapes.or(low, attachment.getCollisionShape(level, pos, context));
        cir.setReturnValue(low);
    }
}
