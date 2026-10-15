package com.meakaandre.siftec.block;

import com.meakaandre.siftec.node.NodeLook;
import com.meakaandre.siftec.node.NodeType;
import com.meakaandre.siftec.node.Purity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A fluid node (the Oil Well and the nitrogen node): unbreakable, never mined, pushed, moved by a contraption or blown
 * up; a Pumpjack or a Resource Well Extractor goes on it. {@code purity} is the node map's purity, copied in when the
 * site is placed. Ore nodes are not this block: they are the real ore block, and a node by their position
 * ({@link com.meakaandre.siftec.node.NodeSites}). The labels here are shared by both.
 */
public class NodeBlock extends Block {
    public static final EnumProperty<Purity> PURITY = EnumProperty.create("purity", Purity.class);
    public final NodeLook look;
    public final NodeType type;

    public NodeBlock(NodeLook look, Properties properties) {
        super(properties);
        this.look = look;
        this.type = look.type();
        registerDefaultState(stateDefinition.any().setValue(PURITY, Purity.NORMAL));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PURITY);
    }

    /** "Iron Ore, Pure". */
    public static Component label(NodeType type, Purity purity) {
        return Component.translatable("siftec.node.label", Component.translatable(type.key()), Component.translatable(purity.key()));
    }

    public static Component label(NodeType type, java.util.Optional<com.meakaandre.siftec.node.Node> node) {
        Component name = Component.translatable(type.key());
        return node.isEmpty() ? name : label(type, node.get().purity());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide()) player.sendOverlayMessage(label(type, state.getValue(PURITY)));
        return InteractionResult.SUCCESS;
    }
}
