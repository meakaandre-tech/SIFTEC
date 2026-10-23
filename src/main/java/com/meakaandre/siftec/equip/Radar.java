package com.meakaandre.siftec.equip;

import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.SpecialBuildings;
import com.meakaandre.siftec.collect.Collectible;
import com.meakaandre.siftec.collect.Collectibles;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.geyser.GeyserSearch;
import com.meakaandre.siftec.item.NodeScannerItem;
import com.meakaandre.siftec.map.MapServer;
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
 * The Radar Tower (a Build Gun building, MAM Quartz: Radar Technology). While it has power it sweeps its range, as in
 * Satisfactory: the company's map has its fog cleared over the whole range (land nobody has loaded is filled in from
 * the world generator), so the crash sites, nodes and geysers there show on it, and the company is told how many
 * crash sites it has newly found. Using it lists the nearest node of each resource the company's scanner knows and
 * the nearest of each collectible.
 */
public final class Radar {
    /** How far a Radar Tower sees: in 96-block cells round it (a little over a kilometre). */
    public static final int CELLS = 11;
    /** How often it sweeps again while powered: every in-game day. */
    public static final long SWEEP_TICKS = 24000;
    private static final int NODE_CELLS = 16, OBJECT_CELLS = 16;
    /** Each kind looked for gets this fraction (1 / n) of the node search time budget. */
    private static final int SEARCH_SHARE = 4;

    private Radar() {
    }

    private static Component line(Component what, double dx, double dz, int x, int z) {
        return Component.translatable("siftec.radar.line", what, (int) Math.round(Math.sqrt(dx * dx + dz * dz)),
            Component.translatable("siftec.direction." + NodeScannerItem.direction(dx, dz)), x, z);
    }

    /** Once a day while powered: clears the company's map over the tower's range and counts the crash sites newly found there. */
    public static void tick(ServerLevel level, ControllerBlockEntity c) {
        if (!c.powered || level.dimension() != Level.OVERWORLD || c.companyId.isEmpty()) return;
        long now = level.getGameTime();
        if (c.lastSweep != 0 && now - c.lastSweep < SWEEP_TICKS && now >= c.lastSweep) return;
        c.lastSweep = now;
        c.setChanged();
        Company company = Companies.byId(level.getServer(), c.companyId);
        if (company == null) return;
        MapServer.radarSweep(level, company, c.getBlockPos(), CELLS * Collectibles.CELL);
        // the geysers in its range, in land nobody has loaded too: the game generates the sulfur cave chunks in the
        // background and their geysers go on the map, within a capped radius (every chunk it generates stays on disk)
        String companyId = company.id;
        int radius = Math.min(GeyserSearch.MAX_RADIUS, CELLS * Collectibles.CELL / 16);
        GeyserSearch.search(level, c.getBlockPos(), radius, geysers -> {
            Company owner = Companies.byId(level.getServer(), companyId);
            if (owner != null && !geysers.isEmpty()) {
                Companies.tell(level.getServer(), owner, Component.translatable("siftec.radar.geysers", geysers.size()).withStyle(ChatFormatting.GOLD));
            }
        });
        Set<String> collected = Set.copyOf(company.collected);
        BlockPos at = c.getBlockPos();
        int cx = Math.floorDiv(at.getX(), Collectibles.CELL), cz = Math.floorDiv(at.getZ(), Collectibles.CELL);
        // the crash sites in range, a row of cells at a time on the worker thread
        List<Integer> rows = new ArrayList<>();
        for (int dx = -CELLS; dx <= CELLS; dx++) rows.add(dx);
        List<Collectibles.Spot> found = new ArrayList<>();
        NodeMap.batched(level.getServer(), rows, 1, batch -> {
            List<Collectibles.Spot> out = new ArrayList<>();
            for (int dx : batch) {
                for (int dz = -CELLS; dz <= CELLS; dz++) {
                    Collectibles.inCell(level, cx + dx, cz + dz).ifPresent(spot -> {
                        if (spot.type() == Collectible.CRASH_SITE && !collected.contains(spot.key())) out.add(spot);
                    });
                }
            }
            return out;
        }, spots -> {
            if (spots != null) found.addAll(spots);
        }, () -> {
            int fresh = 0;
            for (Collectibles.Spot s : found) if (company.revealed.add(s.x() + "," + s.z())) fresh++;
            if (fresh == 0) return;
            Companies.save(level.getServer());
            Companies.tell(level.getServer(), company, Component.translatable("siftec.radar.revealed", fresh).withStyle(ChatFormatting.GOLD));
        });
    }

    /** Using the tower: the nearest node of every resource the company's scanner knows, and the nearest of each collectible. */
    public static void use(ServerPlayer player, ControllerBlockEntity c) {
        if (!(player.level() instanceof ServerLevel server)) return;
        // the survey is the owning company's: what it lists depends on that company's scanner research
        if (!SpecialBuildings.mayOperate(player, c)) {
            player.sendOverlayMessage(Component.translatable("siftec.build_gun.not_yours"));
            return;
        }
        if (!c.powered) {
            player.sendOverlayMessage(Component.translatable("siftec.radar.no_power"));
            return;
        }
        BlockPos pos = c.getBlockPos();
        Company company = Companies.of(player);
        player.sendSystemMessage(Component.translatable("siftec.radar.title").withStyle(ChatFormatting.GOLD));
        List<NodeType> types = new ArrayList<>();
        for (NodeType type : NodeType.values()) if (type.dimension() == server.dimension() && type.onScanner(company)) types.add(type);
        List<Collectible> objects = new ArrayList<>();
        for (Collectible type : Collectible.values()) if (type.scannerToken.isEmpty() || company.hasToken(type.scannerToken)) objects.add(type);
        Set<String> collected = Set.copyOf(company.collected);
        Terrain.Border border = Terrain.Border.of(server);
        // every lookup samples the world generator: one resource at a time on the worker thread, in small pieces
        List<Object> wanted = new ArrayList<>(types);
        if (server.dimension() == Level.OVERWORLD) wanted.addAll(objects);
        List<Component> lines = new ArrayList<>();
        NodeMap.batched(server.getServer(), wanted, 1, batch -> {
            List<Component> out = new ArrayList<>();
            for (Object what : batch) {
                if (what instanceof NodeType type) {
                    Optional<Node> node = NodeMap.nearest(server, pos.getX(), pos.getZ(), type, NODE_CELLS, border, NodeMap.SEARCH_NANOS / SEARCH_SHARE);
                    if (node.isEmpty()) continue;
                    Node n = node.get();
                    out.add(line(NodeBlock.label(type, node), n.x() - pos.getX(), n.z() - pos.getZ(), n.x(), n.z()));
                } else if (what instanceof Collectible type) {
                    Optional<Collectibles.Spot> spot = Collectibles.nearest(server, pos.getX(), pos.getZ(), type, collected, OBJECT_CELLS, border, NodeMap.SEARCH_NANOS / SEARCH_SHARE);
                    if (spot.isEmpty()) continue;
                    Collectibles.Spot s = spot.get();
                    Component name = Component.translatable("siftec.scanner.target." + (type == Collectible.CRASH_SITE ? "hard_drive" : type.id()));
                    out.add(line(name, s.x() - pos.getX(), s.z() - pos.getZ(), s.x(), s.z()).copy().withStyle(ChatFormatting.AQUA));
                }
            }
            return out;
        }, part -> {
            if (part != null) lines.addAll(part);
        }, () -> {
            if (player.isRemoved()) return;
            if (lines.isEmpty()) player.sendSystemMessage(Component.translatable("siftec.radar.nothing"));
            else lines.forEach(player::sendSystemMessage);
        });
    }
}
