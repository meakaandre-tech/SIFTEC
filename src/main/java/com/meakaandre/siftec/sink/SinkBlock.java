package com.meakaandre.siftec.sink;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/** AWESOME Sink: destroys what is put in and pays the company points for it. The Shop is the other mode of the same block class. */
public class SinkBlock extends Block implements EntityBlock {
    public final boolean shop;

    public SinkBlock(boolean shop, Properties properties) {
        super(properties);
        this.shop = shop;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return shop ? null : new SinkBlockEntity(ModBlockEntities.SINK.get(), pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (placer instanceof ServerPlayer player && level.getBlockEntity(pos) instanceof SinkBlockEntity sink) {
            sink.companyId = Companies.of(player).id;
            sink.setChanged();
        }
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (shop || stack.isEmpty()) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (!(player instanceof ServerPlayer server)) return InteractionResult.SUCCESS;
        if (!SinkBlockEntity.accepts(stack)) return InteractionResult.TRY_WITH_EMPTY_HAND;
        Company company = Companies.of(server);
        company.points += SinkBlockEntity.worth(stack);
        stack.setCount(0);
        Companies.save(server.level().getServer());
        server.sendOverlayMessage(Component.translatable("siftec.sink.points", company.points));
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(player instanceof ServerPlayer server)) return InteractionResult.SUCCESS;
        Company company = Companies.of(server);
        if (shop) ShopMenu.open(server, company);
        else server.sendOverlayMessage(Component.translatable("siftec.sink.points", company.points));
        return InteractionResult.SUCCESS;
    }

    // the front looks at whoever placed it
    @Override
    protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<net.minecraft.world.level.block.Block, net.minecraft.world.level.block.state.BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(com.meakaandre.siftec.block.Facing.FACING);
    }

    @Override
    public net.minecraft.world.level.block.state.BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        return com.meakaandre.siftec.block.Facing.place(super.getStateForPlacement(context), context);
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
