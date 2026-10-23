package com.meakaandre.siftec.building;

import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.node.Purity;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The extractors, run by their controllers ({@link BuildingLogic#tick}). Each fills the fluid tank
 * inside it at Satisfactory 1.0's rate while it has power, and its fluid output port drains that tank:
 * <ul>
 * <li>extractor: the Water Extractor (standing in water over its intake, 120 m3 a minute) and the Oil Extractor (over
 * an oil node, 120 m3 a minute on a normal node, half on an impure one, twice on a pure one);</li>
 * <li>pressurizer: the Resource Well Pressurizer over a nitrogen well; it gives nothing itself, but while it runs it
 * pressurizes the well;</li>
 * <li>well_extractor: a Resource Well Extractor within {@link #WELL_RANGE} blocks of a running Pressurizer of its
 * company takes the well's nitrogen, 60 m3 a minute at the well's normal purity. It needs no power, as in Satisfactory.
 * A Pressurizer pressurizes at most {@link #WELL_EXTRACTORS} Extractors, as a Satisfactory well has at most eight
 * satellite nodes: each tick the Extractors are shared out, nearest first, to the nearest Pressurizer with room, and
 * one left over gives nothing and says so ({@code siftec.building.well_full}).</li>
 * </ul>
 */
public final class ExtractorLogic {
    /** How far from a running Pressurizer a Resource Well Extractor still draws on its well. */
    public static final int WELL_RANGE = 32;
    /** The Pressurizers that ran on their last tick: where, when, the well's purity and their company. */
    private record Well(long at, Purity purity, String company) {
    }

    private static final Map<ResourceKey<Level>, Map<BlockPos, Well>> WELLS = new HashMap<>();
    /** How many Extractors one running Pressurizer pressurizes (Satisfactory's wells have up to eight satellite nodes). */
    public static final int WELL_EXTRACTORS = 8;

    /** A Resource Well Extractor that asked for its well recently: when, and its company. */
    private record Asker(long at, String company) {
    }

    /** What an Extractor gets from the wells this tick: the purity it draws at, or null, and whether that is because every Pressurizer in range is full. */
    public record Draw(@Nullable Purity purity, boolean full) {
    }

    private static final Map<ResourceKey<Level>, Map<BlockPos, Asker>> ASKERS = new HashMap<>();
    /** The Extractors shared out to the Pressurizers, per dimension, and the game time that was worked out at. */
    private static final Map<ResourceKey<Level>, Map<BlockPos, Draw>> SHARED = new HashMap<>();
    private static final Map<ResourceKey<Level>, Long> SHARED_AT = new HashMap<>();

    private ExtractorLogic() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            WELLS.clear();
            ASKERS.clear();
            SHARED.clear();
            SHARED_AT.clear();
        });
    }

    static Fluid fluid(BuildingDef def) {
        return BuiltInRegistries.FLUID.getValue(Identifier.parse(def.fluid()));
    }

    /** The purity of the node a node building stands on, or null if no node of its kind is there. */
    static @Nullable Purity nodePurity(Level level, ControllerBlockEntity c, BuildingDef def) {
        if (c.node == null || !level.isLoaded(c.node)) return null;
        BlockState state = level.getBlockState(c.node);
        if (!Buildings.isNode(def, level, c.node) || !state.hasProperty(NodeBlock.PURITY)) return null;
        return state.getValue(NodeBlock.PURITY);
    }

    /** True while the Water Extractor's intake is still a water source. */
    static boolean inWater(Level level, ControllerBlockEntity c, BuildingDef def) {
        Placement p = c.placement();
        int[] i = def.intake();
        if (p == null || i == null) return true;
        var fluid = level.getFluidState(p.at(i[0], i[1], i[2]));
        return fluid.isSource() && fluid.is(FluidTags.WATER);
    }

    static void extract(ServerLevel level, ControllerBlockEntity c, BuildingDef def) {
        float rate = 1f;
        if (def.node()) {
            Purity purity = nodePurity(level, c, def);
            if (purity == null) {
                c.problem = "siftec.building.no_node";
                return;
            }
            rate = purity.multiplier;
        }
        if (!inWater(level, c, def)) {
            c.problem = "siftec.building.no_water";
            return;
        }
        if (!c.powered) {
            c.problem = "siftec.building.stopped";
            return;
        }
        produce(level, c, def, rate);
    }

    static void pressurize(ServerLevel level, ControllerBlockEntity c, BuildingDef def) {
        Purity purity = nodePurity(level, c, def);
        Map<BlockPos, Well> wells = WELLS.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        if (purity == null) {
            c.problem = "siftec.building.no_node";
            wells.remove(c.getBlockPos());
            return;
        }
        if (!c.powered) {
            c.problem = "siftec.building.stopped";
            wells.remove(c.getBlockPos());
            return;
        }
        c.problem = "";
        wells.put(c.getBlockPos().immutable(), new Well(level.getGameTime(), purity, c.companyId));
    }

    /** The well a Resource Well Extractor building draws on now (see {@link #draw}). */
    static Draw well(ServerLevel level, ControllerBlockEntity c) {
        return draw(level, c.getBlockPos(), c.companyId);
    }

    /**
     * What a Resource Well Extractor at {@code pos} of a company draws this tick: the purity of the running Pressurizer of
     * its company within {@link #WELL_RANGE} blocks it is shared out to, or none. Each running Pressurizer takes at most
     * {@link #WELL_EXTRACTORS}: the Extractors are taken nearest to a Pressurizer first (then by position, so the share
     * stays the same from tick to tick), each by the nearest Pressurizer that still has room. Every Extractor that asked
     * within the last few ticks is counted, so one that stops asking (taken down, unloaded) frees its place.
     */
    public static Draw draw(ServerLevel level, BlockPos pos, String company) {
        ResourceKey<Level> dim = level.dimension();
        long now = level.getGameTime();
        Map<BlockPos, Asker> askers = ASKERS.computeIfAbsent(dim, k -> new HashMap<>());
        boolean fresh = askers.put(pos.immutable(), new Asker(now, company)) == null;
        Map<BlockPos, Draw> shared = SHARED.get(dim);
        if (fresh || shared == null || SHARED_AT.getOrDefault(dim, Long.MIN_VALUE) != now || !shared.containsKey(pos)) {
            shared = share(level, askers, now);
            SHARED.put(dim, shared);
            SHARED_AT.put(dim, now);
        }
        Draw d = shared.get(pos);
        return d == null ? new Draw(null, false) : d;
    }

    /** Shares the Extractors that asked recently out to the running Pressurizers (see {@link #draw}). */
    private static Map<BlockPos, Draw> share(ServerLevel level, Map<BlockPos, Asker> askers, long now) {
        askers.values().removeIf(a -> now - a.at() > 5 || a.at() > now);
        Map<BlockPos, Well> wells = WELLS.getOrDefault(level.dimension(), Map.of());
        List<BlockPos> running = new ArrayList<>();
        for (Map.Entry<BlockPos, Well> e : wells.entrySet()) if (now - e.getValue().at() <= 5) running.add(e.getKey());
        double range = (double) WELL_RANGE * WELL_RANGE;
        // each Extractor's Pressurizers in range, nearest first
        Map<BlockPos, List<BlockPos>> reach = new HashMap<>();
        for (Map.Entry<BlockPos, Asker> e : askers.entrySet()) {
            List<BlockPos> near = new ArrayList<>();
            for (BlockPos w : running) {
                if (wells.get(w).company().equals(e.getValue().company()) && w.distSqr(e.getKey()) <= range) near.add(w);
            }
            near.sort(Comparator.comparingDouble((BlockPos w) -> w.distSqr(e.getKey())).thenComparingLong(BlockPos::asLong));
            reach.put(e.getKey(), near);
        }
        List<BlockPos> order = new ArrayList<>(askers.keySet());
        order.sort(Comparator.comparingDouble((BlockPos x) -> reach.get(x).isEmpty() ? Double.MAX_VALUE : reach.get(x).getFirst().distSqr(x))
            .thenComparingLong(BlockPos::asLong));
        Map<BlockPos, Integer> taken = new HashMap<>();
        Map<BlockPos, Draw> out = new HashMap<>();
        for (BlockPos x : order) {
            List<BlockPos> near = reach.get(x);
            Draw d = new Draw(null, !near.isEmpty());
            for (BlockPos w : near) {
                int n = taken.getOrDefault(w, 0);
                if (n >= WELL_EXTRACTORS) continue;
                taken.put(w, n + 1);
                d = new Draw(wells.get(w).purity(), false);
                break;
            }
            out.put(x, d);
        }
        return out;
    }

    static void wellExtract(ServerLevel level, ControllerBlockEntity c, BuildingDef def) {
        Draw d = well(level, c);
        if (d.purity() == null) {
            c.problem = d.full() ? "siftec.building.well_full" : "siftec.building.no_well";
            return;
        }
        produce(level, c, def, d.purity().multiplier);
    }

    /** Fills the tank at the building's rate times the purity; what does not fit waits (the tank is full). */
    private static void produce(ServerLevel level, ControllerBlockEntity c, BuildingDef def, float purity) {
        c.fluidOwed += def.rate() * purity / 1200.0;
        long whole = (long) c.fluidOwed;
        if (whole <= 0) {
            c.problem = "";
            return;
        }
        BlockPos tank = BuildingLogic.cell(c, ProductionLogic.create("fluid_tank"));
        long put = ProductionLogic.fill(level, tank, fluid(def), whole, false);
        c.fluidOwed -= put;
        c.mined += put;
        // a full tank does not bank more than a tick's worth
        if (put < whole) {
            c.fluidOwed = Math.min(c.fluidOwed, def.rate() * purity / 1200.0);
            c.problem = "siftec.building.full";
        } else {
            c.problem = "";
        }
        c.setChanged();
    }

    static void status(Level level, ControllerBlockEntity c, BuildingDef def, List<Component> parts) {
        switch (def.logic()) {
            case "extractor", "well_extractor" -> {
                Purity purity = def.logic().equals("well_extractor") ? (level instanceof ServerLevel s ? well(s, c).purity() : null)
                    : def.node() ? nodePurity(level, c, def) : Purity.NORMAL;
                float perMinute = purity == null ? 0 : def.rate() * purity.multiplier;
                parts.add(Component.translatable("siftec.building.extracting", Math.round(perMinute / ExtractorLogic.mbPerM3()),
                    fluid(def).defaultFluidState().createLegacyBlock().getBlock().getName()));
                parts.add(Component.translatable("siftec.building.extracted", c.mined));
            }
            case "pressurizer" -> {
                Purity purity = nodePurity(level, c, def);
                if (purity != null) parts.add(Component.translatable("siftec.building.pressurized", Component.translatable("siftec.purity." + purity.name().toLowerCase(Locale.ROOT))));
            }
            default -> {
            }
        }
    }

    /** mB in a cubic metre of a Satisfactory fluid (siftec_content.json "mb_per_m3"). */
    static int mbPerM3() {
        var raw = Milestones.raw();
        return raw != null && raw.has("mb_per_m3") ? raw.get("mb_per_m3").getAsInt() : 40;
    }
}
