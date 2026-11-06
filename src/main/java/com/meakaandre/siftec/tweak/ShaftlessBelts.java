package com.meakaandre.siftec.tweak;

import com.meakaandre.siftec.building.BuildingProtection;
import com.meakaandre.siftec.claim.Claims;
import com.zurrtum.create.AllAdvancements;
import com.zurrtum.create.AllBlocks;
import com.zurrtum.create.AllDataComponents;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.content.kinetics.belt.item.BeltConnectorItem;
import com.zurrtum.create.content.kinetics.simpleRelays.ShaftBlock;
import com.zurrtum.create.foundation.block.ProperWaterloggedBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Laying a belt with no shafts: the belt item's first click marks one end, the second the other, and either end may be
 * plain air (the block in front of the clicked face). Create's rules otherwise hold: a straight line, flat, sloped or
 * vertical, at most Create's max belt length, one belt item, and every cell on the way air or replaceable; a building's
 * blocks and other companies' claims refuse it. Two shafts clicked the old way are left to Create.
 * <p>
 * An end laid in air is an ordinary belt end that remembers it had no shaft ({@link End}): it shows no pulley (as every
 * belt does), drops no shaft and leaves none behind when the belt is broken, so a broken belt gives back only what was
 * paid for it. The belt is laid by Create's own code, with a shaft put in each air end for the moment it takes, so it
 * comes out exactly as a belt laid on shafts.
 */
public final class ShaftlessBelts {
    /** What the belt mixin adds to every belt block: whether this end was laid in air, with no shaft. */
    public interface End {
        boolean siftec$noShaft();

        void siftec$noShaft(boolean noShaft);
    }

    private ShaftlessBelts() {
    }

    /** True if this belt block is an end laid with no shaft. */
    public static boolean noShaft(BeltBlockEntity belt) {
        return ((End) belt).siftec$noShaft();
    }

    /** The cell a click lays a belt end in: a shaft or a replaceable block itself, else the block in front of the face. */
    public static BlockPos target(Level level, BlockPos clicked, Direction face) {
        BlockState state = level.getBlockState(clicked);
        return ShaftBlock.isShaft(state) || state.canBeReplaced() ? clicked.immutable() : clicked.relative(face);
    }

    public static boolean isShaft(Level level, BlockPos pos) {
        return level.isLoaded(pos) && ShaftBlock.isShaft(level.getBlockState(pos));
    }

    /**
     * The axis the belt's ends turn about: a shaft end's own axis; with no shaft, across the belt for a flat or sloped
     * one, and for a vertical one across the way the player faces (so its face is toward them). Null for a line no belt
     * can follow.
     */
    public static @Nullable Axis axis(Level level, BlockPos first, BlockPos second, Direction facing) {
        if (isShaft(level, first)) return level.getBlockState(first).getValue(BlockStateProperties.AXIS);
        if (isShaft(level, second)) return level.getBlockState(second).getValue(BlockStateProperties.AXIS);
        BlockPos d = second.subtract(first);
        if (d.getX() != 0 && d.getZ() == 0) return Axis.Z;
        if (d.getZ() != 0 && d.getX() == 0) return Axis.X;
        if (d.getX() == 0 && d.getZ() == 0) return facing.getAxis() == Axis.Z ? Axis.X : Axis.Z;
        return null;
    }

    /** True if the player may put a belt here: no block of a building, and not in someone else's claim. */
    private static boolean mayChange(Level level, BlockPos pos, @Nullable Player player) {
        return !BuildingProtection.protectedAt(level, pos) && (player == null || Claims.allowed(player, level, pos));
    }

    /**
     * Create's {@link BeltConnectorItem#canConnect} for ends that need no shaft: the same line, length and cell rules,
     * an end that is a shaft keeps Create's axis rule, and an end that is not must be air or replaceable.
     */
    public static boolean canConnect(Level level, BlockPos first, BlockPos second, Axis axis, @Nullable Player player) {
        if (!level.isLoaded(first) || !level.isLoaded(second)) return false;
        if (!second.closerThan(first, BeltConnectorItem.maxLength())) return false;
        BlockPos diff = second.subtract(first);
        int x = diff.getX(), y = diff.getY(), z = diff.getZ();
        int sames = (Math.abs(x) == Math.abs(y) ? 1 : 0) + (Math.abs(y) == Math.abs(z) ? 1 : 0) + (Math.abs(z) == Math.abs(x) ? 1 : 0);
        if (axis.choose(x, y, z) != 0 || sames != 1) return false;
        if (axis == Axis.Y && x != 0 && z != 0) return false;
        for (BlockPos end : new BlockPos[]{first, second}) {
            BlockState state = level.getBlockState(end);
            if (ShaftBlock.isShaft(state) ? state.getValue(BlockStateProperties.AXIS) != axis : !state.canBeReplaced()) return false;
            if (!mayChange(level, end, player)) return false;
        }
        BlockPos step = new BlockPos(Integer.signum(x), Integer.signum(y), Integer.signum(z));
        int limit = 1000;
        for (BlockPos at = first.offset(step); !at.equals(second) && limit-- > 0; at = at.offset(step)) {
            BlockState state = level.getBlockState(at);
            boolean shaft = ShaftBlock.isShaft(state) && state.getValue(BlockStateProperties.AXIS) == axis;
            if (!shaft && !state.canBeReplaced()) return false;
            if (!mayChange(level, at, player)) return false;
        }
        return true;
    }

    /**
     * Lays the belt with Create's own {@link BeltConnectorItem#createBelts}: a shaft goes into each air end for the
     * moment it takes, and those ends are then marked as having none.
     */
    public static void lay(Level level, BlockPos first, BlockPos second, Axis axis) {
        List<BlockPos> air = new ArrayList<>();
        for (BlockPos end : new BlockPos[]{first, second}) {
            if (isShaft(level, end)) continue;
            BlockState shaft = AllBlocks.SHAFT.defaultBlockState().setValue(BlockStateProperties.AXIS, axis);
            level.setBlock(end, ProperWaterloggedBlock.withWater(level, shaft, end), Block.UPDATE_ALL);
            air.add(end);
        }
        BeltConnectorItem.createBelts(level, first, second);
        for (BlockPos end : air) {
            if (level.getBlockEntity(end) instanceof BeltBlockEntity belt) {
                ((End) belt).siftec$noShaft(true);
                belt.setChanged();
                belt.sendData();
            } else if (isShaft(level, end)) {
                // Create gave up part way: the stand-in shaft goes again
                level.setBlock(end, ProperWaterloggedBlock.withWater(level, Blocks.AIR.defaultBlockState(), end), Block.UPDATE_ALL);
            }
        }
    }

    /**
     * The belt item used on a block (from the item's mixin): the result if this is a shaftless click, or null to leave
     * it to Create (a sneak-click, a shaft as the first end, or a second shaft after a first one).
     */
    public static @Nullable InteractionResult use(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || player.isShiftKeyDown()) return null;
        Level level = context.getLevel();
        ItemStack stack = context.getItemInHand();
        BlockPos here = target(level, context.getClickedPos(), context.getClickedFace());
        BlockPos first = stack.get(AllDataComponents.BELT_FIRST_SHAFT);
        // a first end that went (built over, or too far off) is forgotten, as Create forgets a broken shaft
        if (first != null && !(isShaft(level, first) || level.isLoaded(first) && level.getBlockState(first).canBeReplaced()
            && first.closerThan(here, BeltConnectorItem.maxLength() * 2))) first = null;
        boolean create = first == null ? isShaft(level, here) : isShaft(level, first) && isShaft(level, here);
        if (create) return null;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (first == null) {
            if (!level.isLoaded(here)) return InteractionResult.FAIL;
            stack.set(AllDataComponents.BELT_FIRST_SHAFT, here);
            player.getCooldowns().addCooldown(stack, 5);
            return InteractionResult.SUCCESS;
        }
        Axis axis = axis(level, first, here, player.getDirection());
        if (axis == null || !canConnect(level, first, here, axis, player)) return InteractionResult.FAIL;
        lay(level, first, here, axis);
        if (player instanceof ServerPlayer server) AllAdvancements.BELT.trigger(server);
        if (!player.isCreative()) stack.shrink(1);
        if (!stack.isEmpty()) {
            stack.remove(AllDataComponents.BELT_FIRST_SHAFT);
            player.getCooldowns().addCooldown(stack, 5);
        }
        return InteractionResult.SUCCESS;
    }
}
