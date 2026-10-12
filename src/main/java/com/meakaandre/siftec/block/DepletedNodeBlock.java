package com.meakaandre.siftec.block;

import com.meakaandre.siftec.node.NodeLook;
import com.meakaandre.siftec.node.NodeRegrowth;
import com.meakaandre.siftec.node.Purity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A node that has just been mined: bare unbreakable stone where the ore was. A scheduled tick (set when it was mined,
 * kept with the chunk) turns it back into its {@link NodeBlock} after its purity's regrowth time.
 */
public class DepletedNodeBlock extends Block {
    public final NodeLook look;

    public DepletedNodeBlock(NodeLook look, Properties properties) {
        super(properties);
        this.look = look;
        registerDefaultState(stateDefinition.any().setValue(NodeBlock.PURITY, Purity.NORMAL));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NodeBlock.PURITY);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        NodeRegrowth.regrow(level, pos, look, state.getValue(NodeBlock.PURITY));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide()) {
            player.sendOverlayMessage(Component.translatable("siftec.node.depleted", NodeBlock.label(look.type(), state.getValue(NodeBlock.PURITY))));
        }
        return InteractionResult.SUCCESS;
    }
}
