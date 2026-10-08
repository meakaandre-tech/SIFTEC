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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import com.meakaandre.siftec.node.Purity;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Optional;

/**
 * The resource part of a node. The node itself is one block with {@code core=true}, standing on the middle of its
 * pad and drawn as a Satisfactory-style rock mound with the resource on top: that is the one a miner has to sit on,
 * which is what keeps it to one miner per node. {@code purity} only picks how big the deposit on it looks (the
 * node map stays the authority). Blocks with {@code core=false} are the ore of mounds from before pads existed.
 */
public class NodeBlock extends Block {
    public static final BooleanProperty CORE = BooleanProperty.create("core");
    public static final EnumProperty<Purity> PURITY = EnumProperty.create("purity", Purity.class);
    public final NodeType type;

    public NodeBlock(NodeType type, Properties properties) {
        super(properties);
        this.type = type;
        registerDefaultState(stateDefinition.any().setValue(CORE, false).setValue(PURITY, Purity.NORMAL));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CORE, PURITY);
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
            Optional<Node> node = NodeMap.near(server, pos.getX(), pos.getZ(), NodePlacer.RADIUS, type);
            player.sendOverlayMessage(label(type, node));
        }
        return InteractionResult.SUCCESS;
    }
}
