package com.meakaandre.siftec.collect;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A slug or artefact sitting in the world. It stays where it is: each company can pick it up once,
 * and gets the item when it does.
 */
public class CollectibleBlock extends Block {
    private static final VoxelShape SHAPE = Block.box(4, 0, 4, 12, 8, 12);
    public final Collectible type;

    public CollectibleBlock(Collectible type, Properties properties) {
        super(properties);
        this.type = type;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(player instanceof ServerPlayer server)) return InteractionResult.SUCCESS;
        Company company = Companies.of(server);
        String key = pos.getX() + "," + pos.getZ();
        if (!company.collected.add(key)) {
            server.sendOverlayMessage(Component.translatable("siftec.collect.already"));
            return InteractionResult.SUCCESS;
        }
        Companies.save(server.level().getServer());
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(com.meakaandre.siftec.Siftec.id(type.id())));
        server.sendOverlayMessage(Component.translatable("siftec.collect.got", stack.getItemName()));
        server.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
        return InteractionResult.SUCCESS;
    }
}
