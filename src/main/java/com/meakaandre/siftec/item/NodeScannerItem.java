package com.meakaandre.siftec.item;

import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.node.Node;
import com.meakaandre.siftec.node.NodeMap;
import com.meakaandre.siftec.node.NodeType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Points to the nearest node of the chosen resource, like Nature's Compass.
 * Right-click scans; sneak and right-click switches resource. Because node positions come from the seed,
 * it finds nodes in land nobody has visited yet.
 */
public class NodeScannerItem extends Item {
    /** How far it looks, in cells of 128 blocks. */
    private static final int RANGE = 64;
    private static final String[] COMPASS = {"south", "south_west", "west", "north_west", "north", "north_east", "east", "south_east"};
    /** What each player has the scanner set to. Kept until the server restarts. */
    private static final Map<UUID, NodeType> SELECTED = new ConcurrentHashMap<>();

    public NodeScannerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!(level instanceof ServerLevel server)) return InteractionResult.SUCCESS;
        NodeType type = SELECTED.getOrDefault(player.getUUID(), NodeType.IRON);
        if (player.isShiftKeyDown()) {
            type = NodeType.values()[(type.ordinal() + 1) % NodeType.values().length];
            SELECTED.put(player.getUUID(), type);
            player.sendOverlayMessage(Component.translatable("siftec.scanner.selected", Component.translatable(type.key())));
            return InteractionResult.SUCCESS;
        }
        if (!NodeMap.hasNodes(level)) {
            player.sendOverlayMessage(Component.translatable("siftec.scanner.no_nodes"));
            return InteractionResult.SUCCESS;
        }
        Optional<Node> found = NodeMap.nearest(server, player.getX(), player.getZ(), type, RANGE);
        if (found.isEmpty()) {
            player.sendOverlayMessage(Component.translatable("siftec.scanner.none", Component.translatable(type.key())));
            return InteractionResult.SUCCESS;
        }
        Node node = found.get();
        int distance = (int) Math.round(node.distanceTo(player.getX(), player.getZ()));
        player.sendOverlayMessage(Component.translatable(
            "siftec.scanner.found", NodeBlock.label(type, found), distance,
            Component.translatable("siftec.direction." + direction(node.x() - player.getX(), node.z() - player.getZ())),
            node.x(), node.z()
        ));
        player.getCooldowns().addCooldown(player.getItemInHand(hand), 20);
        return InteractionResult.SUCCESS;
    }

    /** Compass direction of an offset. South is +z and east is +x. */
    public static String direction(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(-dx, dz));
        int index = Math.floorMod((int) Math.round(angle / 45.0), 8);
        return COMPASS[index];
    }
}
