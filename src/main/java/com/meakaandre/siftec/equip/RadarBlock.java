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
 * Radar Tower: one click lists (and offers as Xaero waypoints) the nearest node of every resource the company's scanner knows, and the
 * nearest slug, artefact and crash site the company has not collected, measured from the tower.
 */
public class RadarBlock extends Block {
    private static final int NODE_CELLS = 16, OBJECT_CELLS = 16;

    public RadarBlock(Properties properties) {
        super(properties);
    }

    /**
     * A waypoint in the form Xaero's Minimap and World Map share over chat
     * ({@code xaero-waypoint:name:initials:x:y:z:colour:use_yaw:yaw:Internal-<dimension>-waypoints}, the dimension
     * written with dashes, such as {@code Internal-the-nether-waypoints}). With either mod installed the player sees
     * the name with an [Add] button; without them it is a line of text. The name is at most 32 characters and has no
     * colons; y is the ground height the generator gives (a number: the format has no "unknown").
     */
    static String waypointText(Level level, String name, int x, int y, int z) {
        String clean = name.replace(':', ' ').replace(',', ' ').trim();
        if (clean.length() > 32) clean = clean.substring(0, 32).trim();
        if (clean.isEmpty()) clean = "SIFTEC";
        String initial = clean.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        String dimension = level.dimension().identifier().getPath().replace('_', '-').replace('/', '-');
        return "xaero-waypoint:" + clean + ":" + initial + ":" + x + ":" + y + ":" + z + ":11:false:0:Internal-" + dimension + "-waypoints";
    }

    private static Component waypoint(Level level, Component what, int x, int y, int z) {
        return Component.literal(waypointText(level, what.getString(), x, y, z));
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
        // every lookup samples the world generator: the whole sweep runs on the node map's worker thread
        java.util.List<NodeType> types = new java.util.ArrayList<>();
        for (NodeType type : NodeType.values()) if (type.dimension() == level.dimension() && type.onScanner(company)) types.add(type);
        java.util.List<Collectible> objects = new java.util.ArrayList<>();
        for (Collectible type : Collectible.values()) if (type.scannerToken.isEmpty() || company.hasToken(type.scannerToken)) objects.add(type);
        java.util.Set<String> collected = java.util.Set.copyOf(company.collected);
        com.meakaandre.siftec.node.Terrain.Border border = com.meakaandre.siftec.node.Terrain.Border.of(server);
        int fallbackY = pos.getY();
        NodeMap.async(server.getServer(), () -> {
            java.util.List<Component> lines = new java.util.ArrayList<>();
            for (NodeType type : types) {
                Optional<Node> node = NodeMap.nearest(server, pos.getX(), pos.getZ(), type, NODE_CELLS, border, NodeMap.SEARCH_NANOS / 4);
                if (node.isEmpty()) continue;
                Node n = node.get();
                int y = NodeMap.groundOf(n).map(g -> g + 1).orElse(fallbackY);
                lines.add(line(NodeBlock.label(type, node), n.x() - pos.getX(), n.z() - pos.getZ(), n.x(), n.z()));
                lines.add(waypoint(level, NodeBlock.label(type, node), n.x(), y, n.z()));
            }
            for (Collectible type : objects) {
                Optional<Collectibles.Spot> spot = Collectibles.nearest(server, pos.getX(), pos.getZ(), type, collected, OBJECT_CELLS, border, NodeMap.SEARCH_NANOS / 4);
                if (spot.isEmpty()) continue;
                Collectibles.Spot s = spot.get();
                var column = com.meakaandre.siftec.node.Terrain.column(server, s.x(), s.z());
                int y = column == null ? fallbackY : column.y() + 1;
                lines.add(line(Component.translatable("item.siftec." + type.id()), s.x() - pos.getX(), s.z() - pos.getZ(), s.x(), s.z()).copy().withStyle(ChatFormatting.AQUA));
                lines.add(waypoint(level, Component.translatable("item.siftec." + type.id()), s.x(), y, s.z()));
            }
            return lines;
        }, lines -> {
            if (player.isRemoved()) return;
            if (lines == null || lines.isEmpty()) player.sendSystemMessage(Component.translatable("siftec.radar.nothing"));
            else lines.forEach(player::sendSystemMessage);
        });
        return InteractionResult.SUCCESS;
    }
}
