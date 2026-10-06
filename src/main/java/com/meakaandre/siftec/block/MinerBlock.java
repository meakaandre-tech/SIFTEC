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
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/** A powered miner. Sits on the middle block of a node and takes rotation from a shaft on top. */
public class MinerBlock extends KineticBlock implements IBE<MinerBlockEntity>, ItemInventoryProvider<MinerBlockEntity> {
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
        });
        return InteractionResult.SUCCESS;
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
}
