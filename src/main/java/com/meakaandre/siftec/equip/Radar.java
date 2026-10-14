package com.meakaandre.siftec.equip;

import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.collect.Collectible;
import com.meakaandre.siftec.collect.Collectibles;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.item.NodeScannerItem;
import com.meakaandre.siftec.node.Node;
import com.meakaandre.siftec.node.NodeMap;
import com.meakaandre.siftec.node.NodeType;
import com.meakaandre.siftec.node.Terrain;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The Radar Tower (a Build Gun building, MAM Quartz: Radar Technology). While it has power it sweeps its range: every
 * crash site in it that the company has not opened is revealed to the company's players as a waypoint for Xaero's
 * Minimap and World Map (once each), and using it lists the nearest node of each resource the company's scanner knows
 * and the nearest of each collectible, with waypoints too.
 */
public final class Radar {
    /** How far a Radar Tower sees: in 96-block cells round it (a little over a kilometre). */
    public static final int CELLS = 11;
    /** How often it sweeps again while powered: every in-game day. */
    public static final long SWEEP_TICKS = 24000;
    private static final int NODE_CELLS = 16, OBJECT_CELLS = 16;

    private Radar() {
    }

    /**
     * A waypoint in the form Xaero's Minimap and World Map share over chat
     * ({@code xaero-waypoint:name:initials:x:y:z:colour:use_yaw:yaw:Internal-<dimension>-waypoints}, the dimension
     * written with dashes, such as {@code Internal-the-nether-waypoints}). With either mod installed the player sees
     * the name with an [Add] button; without them it is a line of text. The name is at most 32 characters and has no
     * colons; y is the ground height the generator gives (a number: the format has no "unknown").
     */
    public static String waypointText(Level level, String name, int x, int y, int z) {
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

    /** Once in a while, while powered: reveals the crash sites in range to the company. */
    public static void tick(ServerLevel level, ControllerBlockEntity c) {
        if (!c.powered || level.dimension() != Level.OVERWORLD || c.companyId.isEmpty()) return;
        long now = level.getGameTime();
        if (c.lastSweep != 0 && now - c.lastSweep < SWEEP_TICKS && now >= c.lastSweep) return;
        c.lastSweep = now;
        c.setChanged();
        Company company = CompanyData.get(level.getServer()).byId(c.companyId);
        if (company == null) return;
        Set<String> collected = Set.copyOf(company.collected);
        BlockPos at = c.getBlockPos();
        int cx = Math.floorDiv(at.getX(), Collectibles.CELL), cz = Math.floorDiv(at.getZ(), Collectibles.CELL);
        NodeMap.async(level.getServer(), () -> {
            List<Collectibles.Spot> found = new ArrayList<>();
            for (int dx = -CELLS; dx <= CELLS; dx++) {
                for (int dz = -CELLS; dz <= CELLS; dz++) {
                    Collectibles.inCell(level, cx + dx, cz + dz).ifPresent(spot -> {
                        if (spot.type() == Collectible.CRASH_SITE && !collected.contains(spot.key())) found.add(spot);
                    });
                }
            }
            List<int[]> out = new ArrayList<>();
            for (Collectibles.Spot s : found) out.add(new int[]{s.x(), Collectibles.y(level, s, at.getY()), s.z()});
            return out;
        }, spots -> {
            if (spots == null) return;
            List<Component> lines = new ArrayList<>();
            for (int[] s : spots) {
                String key = s[0] + "," + s[2];
                if (!company.revealed.add(key)) continue;
                lines.add(waypoint(level, Component.translatable("item.siftec.crash_site"), s[0], s[1], s[2]));
            }
            if (lines.isEmpty()) return;
            Companies.save(level.getServer());
            Companies.tell(level.getServer(), company, Component.translatable("siftec.radar.revealed", lines.size()).withStyle(ChatFormatting.GOLD));
            for (Component l : lines) Companies.tell(level.getServer(), company, l);
        });
    }

    /** Using the tower: the nearest node of every resource the company's scanner knows, and the nearest of each collectible. */
    public static void use(ServerPlayer player, ControllerBlockEntity c) {
        if (!(player.level() instanceof ServerLevel server)) return;
        if (!c.powered) {
            player.sendOverlayMessage(Component.translatable("siftec.radar.no_power"));
            return;
        }
        BlockPos pos = c.getBlockPos();
        Company company = Companies.of(player);
        player.sendSystemMessage(Component.translatable("siftec.radar.title").withStyle(ChatFormatting.GOLD));
        // every lookup samples the world generator: the whole sweep runs on the node map's worker thread
        List<NodeType> types = new ArrayList<>();
        for (NodeType type : NodeType.values()) if (type.dimension() == server.dimension() && type.onScanner(company)) types.add(type);
        List<Collectible> objects = new ArrayList<>();
        for (Collectible type : Collectible.values()) if (type.scannerToken.isEmpty() || company.hasToken(type.scannerToken)) objects.add(type);
        Set<String> collected = Set.copyOf(company.collected);
        Terrain.Border border = Terrain.Border.of(server);
        int fallbackY = pos.getY();
        NodeMap.async(server.getServer(), () -> {
            List<Component> lines = new ArrayList<>();
            for (NodeType type : types) {
                Optional<Node> node = NodeMap.nearest(server, pos.getX(), pos.getZ(), type, NODE_CELLS, border, NodeMap.SEARCH_NANOS / 4);
                if (node.isEmpty()) continue;
                Node n = node.get();
                int y = NodeMap.groundOf(n).map(g -> g + 1).orElse(fallbackY);
                lines.add(line(NodeBlock.label(type, node), n.x() - pos.getX(), n.z() - pos.getZ(), n.x(), n.z()));
                lines.add(waypoint(server, NodeBlock.label(type, node), n.x(), y, n.z()));
            }
            if (server.dimension() == Level.OVERWORLD) {
                for (Collectible type : objects) {
                    Optional<Collectibles.Spot> spot = Collectibles.nearest(server, pos.getX(), pos.getZ(), type, collected, OBJECT_CELLS, border, NodeMap.SEARCH_NANOS / 4);
                    if (spot.isEmpty()) continue;
                    Collectibles.Spot s = spot.get();
                    Component name = Component.translatable("siftec.scanner.target." + (type == Collectible.CRASH_SITE ? "hard_drive" : type.id()));
                    lines.add(line(name, s.x() - pos.getX(), s.z() - pos.getZ(), s.x(), s.z()).copy().withStyle(ChatFormatting.AQUA));
                    lines.add(waypoint(server, name, s.x(), Collectibles.y(server, s, fallbackY), s.z()));
                }
            }
            return lines;
        }, lines -> {
            if (player.isRemoved()) return;
            if (lines == null || lines.isEmpty()) player.sendSystemMessage(Component.translatable("siftec.radar.nothing"));
            else lines.forEach(player::sendSystemMessage);
        });
    }
}
