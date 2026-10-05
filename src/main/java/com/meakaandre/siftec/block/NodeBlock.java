package com.meakaandre.siftec.block;

import com.meakaandre.siftec.node.Node;
import com.meakaandre.siftec.node.NodeMap;
import com.meakaandre.siftec.node.NodePlacer;
import com.meakaandre.siftec.node.NodeType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Optional;

/**
 * The resource part of a node. The block in the middle of the mound has {@code core=true}: that is the one
 * a miner has to sit on, which is what keeps it to one miner per node.
 */
public class NodeBlock extends Block {
    public static final BooleanProperty CORE = BooleanProperty.create("core");
    public final NodeType type;

    public NodeBlock(NodeType type, Properties properties) {
        super(properties);
        this.type = type;
        registerDefaultState(stateDefinition.any().setValue(CORE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CORE);
    }

    /** "Iron Ore, Pure". Purity is not visible on the block; you have to look. */
    public static Component label(NodeType type, Optional<Node> node) {
        Component name = Component.translatable(type.key());
        if (node.isEmpty()) return name;
        return Component.translatable("siftec.node.label", name, Component.translatable(node.get().purity().key()));
    }

    @Override
    protected InteractionResult useWithoutItem(
        BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit
    ) {
        if (level instanceof ServerLevel server) {
            Optional<Node> node = NodeMap.near(server, pos.getX(), pos.getZ(), NodePlacer.RADIUS);
            player.sendOverlayMessage(label(type, node));
        }
        return InteractionResult.SUCCESS;
    }
}
