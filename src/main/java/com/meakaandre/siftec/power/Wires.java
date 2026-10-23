package com.meakaandre.siftec.power;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.BuildingIndex;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.mixin.power.ConnectorAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.antarcticgardens.cna.config.CNAConfig;
import org.antarcticgardens.cna.content.electricity.connector.AbstractElectricalConnector;
import org.antarcticgardens.cna.content.electricity.wire.WireType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The pack's rules for New Age's wires, and keeping the {@link WireMap} in step with them.
 * <ul>
 * <li>A wire between two Power Towers may be up to 256 blocks long; between two connectors of Build Gun buildings
 * (poles, buildings) up to 32; anything else keeps New Age's own limit.</li>
 * <li>A connector of a Build Gun building takes only as many wires as the building allows (a Power Pole Mk.1 four, a
 * Mk.2 seven, a Mk.3 ten, a Tower ten, any other building one, as in Satisfactory).</li>
 * </ul>
 */
public final class Wires {
    public static final int TOWER_REACH = 256, BUILDING_REACH = 32;

    private Wires() {
    }

    // ------------------------------------------------------------------------------------------------ rules

    /** The Build Gun building a connector belongs to, or null. */
    public static @Nullable BuildingDef building(Level level, BlockPos connector) {
        BlockPos controller = BuildingIndex.controllerOf(level, connector);
        return controller != null && level.getBlockEntity(controller) instanceof ControllerBlockEntity c ? c.def() : null;
    }

    /** True if the connector belongs to a building of the HUB (its burners' connectors). */
    private static boolean prefab(Level level, BlockPos connector) {
        BlockPos controller = BuildingIndex.controllerOf(level, connector);
        return controller != null && !(level.getBlockEntity(controller) instanceof ControllerBlockEntity);
    }

    /** How many wires a connector may hold (0: no limit). */
    public static int maxLines(Level level, BlockPos connector) {
        BuildingDef def = building(level, connector);
        if (def != null) return def.lines() > 0 ? def.lines() : 1;
        return prefab(level, connector) ? 1 : 0;
    }

    /** How long a wire between these two connectors may be. */
    public static int maxLength(Level level, BlockPos a, BlockPos b) {
        BuildingDef da = building(level, a), db = building(level, b);
        if (da != null && db != null && da.id().equals("power_tower") && db.id().equals("power_tower")) return TOWER_REACH;
        boolean ours = (da != null || prefab(level, a)) && (db != null || prefab(level, b));
        return Math.max(ours ? BUILDING_REACH : 0, newAgeLength());
    }

    /** The longest wire a connector could have at all: what a wire bound to it may be carried that far for. */
    public static int reach(Level level, BlockPos a) {
        BuildingDef da = building(level, a);
        if (da != null && da.id().equals("power_tower")) return TOWER_REACH;
        return Math.max(da != null || prefab(level, a) ? BUILDING_REACH : 0, newAgeLength());
    }

    public static int newAgeLength() {
        try {
            return CNAConfig.getServer().maxWireLength.get();
        } catch (RuntimeException e) {
            Siftec.LOGGER.debug("SIFTEC: Create: New Age's maxWireLength could not be read; using 16", e);
            return 16;
        }
    }

    /** What stops a wire between these two connectors, as a lang key, or null if it may go. */
    public static @Nullable String refuse(Level level, AbstractElectricalConnector a, AbstractElectricalConnector b) {
        int la = maxLines(level, a.getBlockPos()), lb = maxLines(level, b.getBlockPos());
        if (la > 0 && a.getConnectorPositions().size() >= la || lb > 0 && b.getConnectorPositions().size() >= lb) return "siftec.power.lines_full";
        int max = maxLength(level, a.getBlockPos(), b.getBlockPos());
        if (a.getBlockPos().distSqr(b.getBlockPos()) > (double) max * max) return "siftec.power.too_long";
        return null;
    }

    // ------------------------------------------------------------------------------------------------ the map

    /**
     * A connector seen for the first time since it loaded: the map is the truth for wires cut while it was away (their
     * wire item already came back where they were cut); a connector the map has never seen tells the map its wires.
     */
    static void joinMap(ServerLevel level, AbstractElectricalConnector connector) {
        WireMap map = WireMap.get(level);
        BlockPos pos = connector.getBlockPos();
        Map<BlockPos, WireType> positions = ((ConnectorAccessor) connector).siftec$positions();
        if (!map.knows(level, pos)) {
            if (!positions.isEmpty()) map.add(level, pos, new ArrayList<>(positions.keySet()));
            return;
        }
        Set<BlockPos> kept = map.links(level, pos);
        // a wire the map has but this connector does not cannot be real (a wire always goes into both): it goes
        for (BlockPos end : kept) if (!positions.containsKey(end)) map.unlink(level, pos, end);
        if (positions.keySet().removeIf(end -> !kept.contains(end))) {
            connector.setChanged();
            connector.sendData();
        }
    }

    /** A wire was strung. */
    public static void linked(AbstractElectricalConnector a, AbstractElectricalConnector b) {
        if (a.getLevel() instanceof ServerLevel level) WireMap.get(level).link(level, a.getBlockPos(), b.getBlockPos());
    }

    /** A wire was cut. */
    public static void unlinked(AbstractElectricalConnector a, AbstractElectricalConnector b) {
        if (a.getLevel() instanceof ServerLevel level) WireMap.get(level).unlink(level, a.getBlockPos(), b.getBlockPos());
    }

    /**
     * A connector is broken. New Age gives back the wires to connectors that are loaded; the ones to connectors that
     * are not come back here too, and the map lets go of them (those connectors drop their end when they load).
     */
    public static void removed(AbstractElectricalConnector connector, Level level) {
        if (!(level instanceof ServerLevel server)) return;
        Map<BlockPos, WireType> positions = ((ConnectorAccessor) connector).siftec$positions();
        Map<AbstractElectricalConnector, WireType> joined = ((ConnectorAccessor) connector).siftec$connectors();
        List<ItemStack> drops = new ArrayList<>();
        for (Map.Entry<BlockPos, WireType> e : positions.entrySet()) {
            boolean viaNewAge = false;
            for (AbstractElectricalConnector other : joined.keySet()) if (other.getBlockPos().equals(e.getKey())) viaNewAge = true;
            if (!viaNewAge) drops.add(e.getValue().getDroppedItem());
        }
        if (!drops.isEmpty()) Containers.dropContents(level, connector.getBlockPos(), NonNullList.of(ItemStack.EMPTY, drops.toArray(new ItemStack[0])));
        WireMap.get(server).remove(server, connector.getBlockPos());
        PowerMemory.get(server).forget(server, connector.getBlockPos());
        PowerGrids.dirty();
    }
}
