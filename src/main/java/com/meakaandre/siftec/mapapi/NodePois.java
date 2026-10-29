package com.meakaandre.siftec.mapapi;

import com.meakaandre.mapmod.api.MapPoi;
import com.meakaandre.mapmod.api.PoiArea;
import com.meakaandre.mapmod.api.PoiSource;
import com.meakaandre.siftec.collect.Collectible;
import com.meakaandre.siftec.collect.Collectibles;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.node.Node;
import com.meakaandre.siftec.node.NodeMap;
import com.meakaandre.siftec.node.NodeType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * What the seed puts in a region for the map, as far as a company knows it: resource nodes and wells whose resource
 * its scanner has, crash sites and artifacts its Object Scanner can find and it has not collected yet. Worked out on
 * the node map's worker (where the node map's own work runs), and again whenever the company's milestones or collected
 * things change.
 */
public final class NodePois {
    /** Multiplier mixing the done-count hash into a company's stamp. */
    private static final int STAMP_HASH = 31;

    private NodePois() {
    }

    /** What a company is allowed to see of the seed's things, read on the server thread for the worker. */
    public record Knows(Set<NodeType> nodes, Set<Collectible> objects, Set<String> collected) {
        static final Knows NOTHING = new Knows(Set.of(), Set.of(), Set.of());

        public static Knows of(@Nullable Company company) {
            if (company == null) return NOTHING;
            Set<NodeType> nodes = EnumSet.noneOf(NodeType.class);
            for (NodeType type : NodeType.values()) if (type.dimension() == Level.OVERWORLD && type.onScanner(company)) nodes.add(type);
            Set<Collectible> objects = EnumSet.noneOf(Collectible.class);
            for (Collectible c : Collectible.values()) if (c.scannerToken.isEmpty() || company.hasToken(c.scannerToken)) objects.add(c);
            return new Knows(nodes, objects, Set.copyOf(company.collected));
        }
    }

    public static final PoiSource<Knows> SOURCE = new PoiSource<>() {
        @Override
        public Knows prepare(ServerLevel level, String group) {
            return Knows.of(Companies.byId(level.getServer(), group));
        }

        @Override
        public List<MapPoi> pois(ServerLevel level, PoiArea area, Knows knows) {
            return seedPois(level, area, knows);
        }

        @Override
        public int stamp(MinecraftServer server, String group) {
            Company company = Companies.byId(server, group);
            return company == null ? 0 : company.done.hashCode() * STAMP_HASH + company.collected.size();
        }

        @Override
        public boolean ready(ServerLevel level) {
            return NodeMap.hasNodes(level) && NodeMap.origin() != null;
        }

        @Override
        public Executor executor() {
            return NodeMap.executor();
        }
    };

    /** Off the server thread: the nodes, wells and collectibles the seed puts in an area, as far as the company knows. */
    public static List<MapPoi> seedPois(ServerLevel level, PoiArea area, Knows knows) {
        List<MapPoi> out = new ArrayList<>();
        int minX = area.minX(), minZ = area.minZ(), maxX = area.maxX(), maxZ = area.maxZ();
        for (int cx = Math.floorDiv(minX, NodeMap.CELL); cx <= Math.floorDiv(maxX - 1, NodeMap.CELL); cx++) {
            for (int cz = Math.floorDiv(minZ, NodeMap.CELL); cz <= Math.floorDiv(maxZ - 1, NodeMap.CELL); cz++) {
                for (Node node : NodeMap.inCell(level, cx, cz)) {
                    if (!area.contains(node.x(), node.z()) || !knows.nodes.contains(node.type())) continue;
                    // a cave or Nether node with nowhere to stand was left out of the world: not on the map either
                    if (NodeMap.placeless(node)) continue;
                    int y = NodeMap.groundOf(node).map(g -> g + 1).orElse(level.getSeaLevel());
                    out.add(MapPoi.of(node.type().isFluid() ? SiftecMap.WELL : SiftecMap.NODE, node.type().id(), node.purity().ordinal(), node.x(), y, node.z()));
                }
            }
        }
        for (int cx = Math.floorDiv(minX, Collectibles.CELL); cx <= Math.floorDiv(maxX - 1, Collectibles.CELL); cx++) {
            for (int cz = Math.floorDiv(minZ, Collectibles.CELL); cz <= Math.floorDiv(maxZ - 1, Collectibles.CELL); cz++) {
                Collectibles.Spot spot = Collectibles.inCell(level, cx, cz).orElse(null);
                if (spot == null || !area.contains(spot.x(), spot.z())) continue;
                if (!knows.objects.contains(spot.type()) || knows.collected.contains(spot.key())) continue;
                var kind = switch (spot.type()) {
                    case CRASH_SITE -> SiftecMap.CRASH_SITE;
                    case MERCER_SPHERE -> SiftecMap.MERCER_SPHERE;
                    case SOMERSLOOP -> SiftecMap.SOMERSLOOP;
                };
                out.add(MapPoi.of(kind, spot.x(), Collectibles.y(level, spot, level.getSeaLevel()), spot.z()));
            }
        }
        return out;
    }
}
