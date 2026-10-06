package com.meakaandre.siftec.equip;

import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.collect.Collectible;
import com.meakaandre.siftec.collect.Collectibles;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.item.NodeScannerItem;
import com.meakaandre.siftec.node.Node;
import com.meakaandre.siftec.node.NodeMap;
import com.meakaandre.siftec.node.NodeType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Optional;

/**
 * Radar Tower: one click lists the nearest node of every resource the company's scanner knows, and the
 * nearest slug, artefact and crash site the company has not collected, measured from the tower.
 */
public class RadarBlock extends Block {
    private static final int NODE_CELLS = 16, OBJECT_CELLS = 16;

    public RadarBlock(Properties properties) {
        super(properties);
    }

    private static Component line(Component what, double dx, double dz, int x, int z) {
        return Component.translatable("siftec.radar.line", what, (int) Math.round(Math.sqrt(dx * dx + dz * dz)),
            Component.translatable("siftec.direction." + NodeScannerItem.direction(dx, dz)), x, z);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player user, BlockHitResult hit) {
        if (!(level instanceof ServerLevel server) || !(user instanceof ServerPlayer player)) return InteractionResult.SUCCESS;
        Company company = Companies.of(player);
        player.sendSystemMessage(Component.translatable("siftec.radar.title").withStyle(ChatFormatting.GOLD));
        int found = 0;
        for (NodeType type : NodeType.values()) {
            if (type.dimension() != level.dimension() || !type.onScanner(company)) continue;
            Optional<Node> node = NodeMap.nearest(server, pos.getX(), pos.getZ(), type, NODE_CELLS);
            if (node.isEmpty()) continue;
            player.sendSystemMessage(line(NodeBlock.label(type, node), node.get().x() - pos.getX(), node.get().z() - pos.getZ(), node.get().x(), node.get().z()));
            found++;
        }
        for (Collectible type : Collectible.values()) {
            if (!type.scannerToken.isEmpty() && !company.hasToken(type.scannerToken)) continue;
            Optional<Collectibles.Spot> spot = Collectibles.nearest(server, pos.getX(), pos.getZ(), type, company.collected, OBJECT_CELLS);
            if (spot.isEmpty()) continue;
            player.sendSystemMessage(line(Component.translatable("item.siftec." + type.id()), spot.get().x() - pos.getX(), spot.get().z() - pos.getZ(),
                spot.get().x(), spot.get().z()).copy().withStyle(ChatFormatting.AQUA));
            found++;
        }
        if (found == 0) player.sendSystemMessage(Component.translatable("siftec.radar.nothing"));
        return InteractionResult.SUCCESS;
    }
}
