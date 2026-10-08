package com.meakaandre.siftec.block;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.registry.ModBlockEntities;
import com.meakaandre.siftec.registry.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import com.zurrtum.create.content.kinetics.base.KineticBlock;
import com.zurrtum.create.foundation.block.IBE;
import com.zurrtum.create.infrastructure.items.ItemInventoryProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * A powered miner, shaped like the old node drills: two blocks tall. This is the lower block, which sits on the
 * node and holds the drill, its output and its fluid tank; the upper block ({@link MinerTopBlock}) is the top of
 * the casing and passes rotation down from a shaft above. One model draws the whole machine from here: the casing
 * running up into the block above, the lip and ring of beams at its foot and four legs down to the pad. The
 * renderer adds Create's drill head (resting on the node), the cog on top and the shaft end, all turning with
 * the shaft. Pipes fill the tank from any side of either block; funnels take the output from this block.
 */
public class MinerBlock extends KineticBlock implements IBE<MinerBlockEntity>, ItemInventoryProvider<MinerBlockEntity>,
    com.zurrtum.create.infrastructure.fluids.FluidInventoryProvider<MinerBlockEntity> {
    public final MinerTier tier;

    public MinerBlock(MinerTier tier, Properties properties) {
        super(properties);
        this.tier = tier;
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == Direction.UP;
    }

    @Override
    public Axis getRotationAxis(BlockState state) {
        return Axis.Y;
    }

    @Override
    protected InteractionResult useWithoutItem(
        BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit
    ) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        withBlockEntityDo(level, pos, miner -> {
            if (player.isShiftKeyDown()) miner.ejectBoosts();
            else miner.giveTo(player);
            player.sendOverlayMessage(miner.describe());
        });
        return InteractionResult.SUCCESS;
    }

    /** Two blocks tall: it needs room above for the top of its casing. */
    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        BlockPos above = context.getClickedPos().above();
        if (above.getY() > context.getLevel().getMaxY() || !context.getLevel().getBlockState(above).canBeReplaced(context)) return null;
        return com.meakaandre.siftec.block.Facing.place(super.getStateForPlacement(context), context);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide()) placeTop(level, pos);
    }

    /** Puts the upper block on a miner (placing by hand does it; commands and tests call this). */
    public static void placeTop(Level level, BlockPos pos) {
        if (level.getBlockState(pos.above()).canBeReplaced()) {
            level.setBlock(pos.above(), com.meakaandre.siftec.registry.ModBlocks.MINER_TOP.get().defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    @Override
    public com.zurrtum.create.infrastructure.fluids.@Nullable FluidInventory getFluidInventory(
        LevelAccessor world, BlockPos pos, BlockState state, MinerBlockEntity be, @Nullable Direction side
    ) {
        return side == Direction.DOWN ? null : be.tank.getCapability();
    }

    /** Power Shards and a Somersloop are slotted in by clicking the miner with them. */
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        boolean shard = stack.is(ModItems.PARTS.get("power_shard")), loop = stack.is(ModItems.PARTS.get("somersloop"));
        if (!shard && !loop) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (!(player instanceof ServerPlayer server)) return InteractionResult.SUCCESS;
        Company company = Companies.of(server);
        withBlockEntityDo(level, pos, miner -> {
            boolean both = company.hasToken("amplifier:shards") || server.hasInfiniteMaterials();
            if (shard) {
                int slots = server.hasInfiniteMaterials() ? 3 : company.best("shards:", 0);
                if (miner.shards >= slots || miner.amplified && !both) {
                    server.sendOverlayMessage(Component.translatable("siftec.boost.no_slot"));
                    return;
                }
                miner.shards++;
            } else {
                if (miner.amplified || !(company.hasToken("amplifier") || server.hasInfiniteMaterials()) || miner.shards > 0 && !both) {
                    server.sendOverlayMessage(Component.translatable("siftec.boost.no_slot"));
                    return;
                }
                miner.amplified = true;
            }
            if (!server.hasInfiniteMaterials()) stack.shrink(1);
            miner.boostChanged();
            server.sendOverlayMessage(Component.translatable("siftec.boost.status", miner.shards, Component.translatable(miner.amplified ? "siftec.boost.yes" : "siftec.boost.no")));
        });
        return InteractionResult.SUCCESS;
    }

    @Override
    public @Nullable Container getInventory(
        LevelAccessor world, BlockPos pos, BlockState state, MinerBlockEntity blockEntity, @Nullable Direction context
    ) {
        return blockEntity.output;
    }

    @Override
    public Class<MinerBlockEntity> getBlockEntityClass() {
        return MinerBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends MinerBlockEntity> getBlockEntityType() {
        return ModBlockEntities.MINER.get();
    }

    // the front looks at whoever placed it
    @Override
    protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<net.minecraft.world.level.block.Block, net.minecraft.world.level.block.state.BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(com.meakaandre.siftec.block.Facing.FACING);
    }


    @Override
    protected net.minecraft.world.level.block.state.BlockState rotate(net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.block.Rotation rotation) {
        return com.meakaandre.siftec.block.Facing.rotate(state, rotation);
    }

    @Override
    protected net.minecraft.world.level.block.state.BlockState mirror(net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.block.Mirror mirror) {
        return com.meakaandre.siftec.block.Facing.mirror(state, mirror);
    }
}
