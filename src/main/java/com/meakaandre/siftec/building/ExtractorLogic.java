package com.meakaandre.siftec.building;

import com.meakaandre.siftec.block.NodeBlock;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The extractors of work package 9, run by their controllers ({@link BuildingLogic#tick}). Each fills the fluid tank
 * inside it at Satisfactory 1.0's rate while it has power, and its fluid output port drains that tank:
 * <ul>
 * <li>extractor: the Water Extractor (standing in water over its intake, 120 m3 a minute) and the Oil Extractor (over
 * an oil node, 120 m3 a minute on a normal node, half on an impure one, twice on a pure one);</li>
 * <li>pressurizer: the Resource Well Pressurizer over a nitrogen well; it gives nothing itself, but while it runs it
 * pressurizes the well;</li>
 * <li>well_extractor: a Resource Well Extractor within {@link #WELL_RANGE} blocks of a running Pressurizer of its
 * company takes the well's nitrogen, 60 m3 a minute at the well's normal purity. It needs no power, as in Satisfactory.</li>
 * </ul>
 */
public final class ExtractorLogic {
    /** How far from a running Pressurizer a Resource Well Extractor still draws on its well. */
    public static final int WELL_RANGE = 32;
    /** The Pressurizers that ran on their last tick: where, when, the well's purity and their company. */
    private record Well(long at, Purity purity, String company) {
    }

    private static final Map<ResourceKey<Level>, Map<BlockPos, Well>> WELLS = new HashMap<>();

    private ExtractorLogic() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> WELLS.clear());
    }

    static Fluid fluid(BuildingDef def) {
        return BuiltInRegistries.FLUID.getValue(Identifier.parse(def.fluid()));
    }

    /** The purity of the node a node building stands on, or null if no node of its kind is there. */
    static @Nullable Purity nodePurity(Level level, ControllerBlockEntity c, BuildingDef def) {
        if (c.node == null || !level.isLoaded(c.node)) return null;
        BlockState state = level.getBlockState(c.node);
        if (!Buildings.isNode(def, state) || !state.hasProperty(NodeBlock.PURITY)) return null;
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

    /** The running Pressurizer a Resource Well Extractor draws on: the nearest of its company within range. */
    static @Nullable Purity well(ServerLevel level, ControllerBlockEntity c) {
        Map<BlockPos, Well> wells = WELLS.get(level.dimension());
        if (wells == null) return null;
        long now = level.getGameTime();
        Purity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Map.Entry<BlockPos, Well> e : wells.entrySet()) {
            Well w = e.getValue();
            if (now - w.at() > 5 || !w.company().equals(c.companyId)) continue;
            double d = e.getKey().distSqr(c.getBlockPos());
            if (d > (double) WELL_RANGE * WELL_RANGE || d >= bestDist) continue;
            bestDist = d;
            best = w.purity();
        }
        return best;
    }

    static void wellExtract(ServerLevel level, ControllerBlockEntity c, BuildingDef def) {
        Purity purity = well(level, c);
        if (purity == null) {
            c.problem = "siftec.building.no_well";
            return;
        }
        produce(level, c, def, purity.multiplier);
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
                Purity purity = def.logic().equals("well_extractor") && level instanceof ServerLevel s ? well(s, c)
                    : def.node() ? nodePurity(level, c, def) : Purity.NORMAL;
                float perMinute = purity == null ? 0 : def.rate() * purity.multiplier;
                parts.add(Component.translatable("siftec.building.extracting", Math.round(perMinute / com.meakaandre.siftec.building.ExtractorLogic.mbPerM3()),
                    fluid(def).defaultFluidState().createLegacyBlock().getBlock().getName()));
                parts.add(Component.translatable("siftec.building.extracted", c.mined));
            }
            case "pressurizer" -> {
                Purity purity = nodePurity(level, c, def);
                if (purity != null) parts.add(Component.translatable("siftec.building.pressurized", Component.translatable("siftec.purity." + purity.name().toLowerCase(java.util.Locale.ROOT))));
            }
            default -> {
            }
        }
    }

    /** mB in a cubic metre of a Satisfactory fluid (siftec_content.json "mb_per_m3"). */
    static int mbPerM3() {
        var raw = com.meakaandre.siftec.hub.Milestones.raw();
        return raw != null && raw.has("mb_per_m3") ? raw.get("mb_per_m3").getAsInt() : 40;
    }
}
