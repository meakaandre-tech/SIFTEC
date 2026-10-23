package com.meakaandre.siftec.collect;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.node.NodeMap;
import com.meakaandre.siftec.power.Power;
import com.meakaandre.siftec.power.PowerGrids;
import com.meakaandre.siftec.save.JsonSavedData;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.antarcticgardens.cna.content.electricity.connector.AbstractElectricalConnector;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * Crash sites: what a cargo pod wants before it opens, and the wreckage lying round it. As in Satisfactory, some open
 * freely, some want parts delivered, and some want power: a Create: New Age wire from a grid with that much power to
 * spare, strung to the connector beside the pod. They want more the further they landed from spawn.
 */
public final class CrashSites {
    /** Pods within this distance of world spawn open freely; power is only asked for beyond {@link #POWER_FROM}. */
    private static final int FREE = 400, STEP = 500, POWER_FROM = 900;
    /** What pods ask for, easiest first. A pod picks from the first few; further out it can pick from more. */
    private static final String[][] WANTS = {
        {"siftec:iron_rod", "30"}, {"create:iron_sheet", "30"}, {"siftec:wire", "50"}, {"siftec:screw", "50"}, {"siftec:rotor", "20"},
        {"siftec:reinforced_iron_plate", "15"}, {"siftec:cable", "50"}, {"siftec:modular_frame", "10"}, {"siftec:steel_beam", "30"},
        {"siftec:steel_pipe", "30"}, {"siftec:stator", "10"}, {"siftec:motor", "5"}, {"siftec:circuit_board", "10"}, {"siftec:computer", "3"},
        {"siftec:heavy_modular_frame", "2"}, {"siftec:radio_control_unit", "1"}};
    /** The megawatts a powered pod asks a grid to have spare: more the further out, up to the last. */
    private static final int[] POWER_MW = {5, 10, 20, 30, 50};

    private CrashSites() {
    }

    /** What a pod asks for before it opens: nothing, parts, or a powered grid. */
    public record Requirement(Kind kind, Milestone.@Nullable Cost cost, int mw) {
        public enum Kind {FREE, PARTS, POWER}

        static final Requirement NONE = new Requirement(Kind.FREE, null, 0);
    }

    private static long hash(BlockPos pos) {
        long v = pos.getX() * 0x9E3779B97F4A7C15L ^ pos.getZ() * 0xC2B2AE3D27D4EB4FL;
        v ^= v >>> 31;
        v *= 0xff51afd7ed558ccdL;
        return v ^ (v >>> 33);
    }

    /**
     * Blocks from the point the map is measured from. Asked on the node map's worker thread too, so it reads the settled
     * origin ({@link NodeMap#origin}, safe from any thread), never the saved data.
     */
    private static double fromSpawn(ServerLevel level, BlockPos pos) {
        int[] origin = NodeMap.origin();
        int ox = origin == null ? 0 : origin[0], oz = origin == null ? 0 : origin[1];
        return Math.sqrt(Math.pow(pos.getX() - ox, 2) + Math.pow(pos.getZ() - oz, 2));
    }

    /** What this pod asks for. Worked out from where it is (x and z), so it never changes. */
    public static Requirement requirement(ServerLevel level, BlockPos pos) {
        double distance = fromSpawn(level, pos);
        long h = hash(pos);
        if (distance < FREE || Long.remainderUnsigned(h, 4) == 0) return Requirement.NONE;
        int steps = (int) ((distance - FREE) / STEP);
        if (distance >= POWER_FROM && Long.remainderUnsigned(h >>> 40, 3) == 0) {
            return new Requirement(Requirement.Kind.POWER, null, POWER_MW[Math.min(POWER_MW.length - 1, (int) ((distance - POWER_FROM) / STEP))]);
        }
        int reach = Math.min(WANTS.length, 3 + steps * 2);
        String[] want = WANTS[(int) Long.remainderUnsigned(h >>> 8, reach)];
        return new Requirement(Requirement.Kind.PARTS, new Milestone.Cost(want[0], Integer.parseInt(want[1])), 0);
    }

    /** One pod in five that landed well away from spawn also carries a Somersloop. */
    public static boolean hasSomersloop(ServerLevel level, BlockPos pos) {
        return fromSpawn(level, pos) >= 1000 && Long.remainderUnsigned(hash(pos) >>> 20, 5) == 0;
    }

    /** The block a powered pod's wire connector stands in: the first side of the pod with room and ground under it. */
    public static @Nullable BlockPos connectorSpot(ServerLevel level, BlockPos pod) {
        return connectorSpot(level, pod, null);
    }

    /** As above, looking only inside {@code only} when given (world generation never reads a neighbouring chunk). */
    private static @Nullable BlockPos connectorSpot(ServerLevel level, BlockPos pod, @Nullable ChunkPos only) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos at = pod.relative(side);
            if (Collectibles.inside(only, at) && isConnector(level.getBlockState(at))) return at;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos at = pod.relative(side);
            if (!Collectibles.inside(only, at)) continue;
            if (level.getBlockState(at).canBeReplaced() && level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)) return at;
        }
        return null;
    }

    private static boolean isConnector(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(Identifier.fromNamespaceAndPath("create_new_age", "electrical_connector"));
    }

    /** A Create: New Age wire connector standing on the ground. */
    private static @Nullable BlockState connector() {
        Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.fromNamespaceAndPath("create_new_age", "electrical_connector")).orElse(null);
        if (block == null) return null;
        BlockState state = block.defaultBlockState();
        return state.hasProperty(BlockStateProperties.FACING) ? state.setValue(BlockStateProperties.FACING, Direction.UP) : state;
    }

    /** Which pods have had their wire connector put down, kept with the world ("dimension|x,y,z" of the pod). */
    public static class Data extends JsonSavedData<Data.Stored> {
        public static class Stored {
            Set<String> connectors = new HashSet<>();
        }

        static final Codec<Data> CODEC = JsonSavedData.codec(Data::new);
        static final SavedDataType<Data> TYPE =
            new SavedDataType<>(Siftec.id("crash_sites"), Data::new, CODEC, null);

        public Data() {
            super("crash_sites", Stored.class, Stored::new);
        }

        static Data get(MinecraftServer server) {
            return server.getDataStorage().computeIfAbsent(TYPE);
        }
    }

    private static String podKey(ServerLevel level, BlockPos pod) {
        return level.dimension().identifier() + "|" + pod.getX() + "," + pod.getY() + "," + pod.getZ();
    }

    /** True if this pod's wire connector has been put down (it may have been taken away since). */
    public static boolean connectorPlaced(ServerLevel level, BlockPos pod) {
        return Data.get(level.getServer()).stored.connectors.contains(podKey(level, pod));
    }

    /** Forgets that the pod's connector was put down (the selftests' own pods). */
    public static void forgetConnector(ServerLevel level, BlockPos pod) {
        Data data = Data.get(level.getServer());
        if (data.stored.connectors.remove(podKey(level, pod))) data.setDirty();
    }

    /**
     * Puts the pod's wire connector beside it, once per pod: one taken away is not put back (it would be a free
     * connector each time). True if one is there now.
     */
    public static boolean placeConnector(ServerLevel level, BlockPos pod) {
        return placeConnector(level, pod, null);
    }

    /** As above; with {@code only}, the connector goes on a side inside that chunk or (for now) nowhere. */
    private static boolean placeConnector(ServerLevel level, BlockPos pod, @Nullable ChunkPos only) {
        BlockPos at = connectorSpot(level, pod, only);
        Data data = Data.get(level.getServer());
        String key = podKey(level, pod);
        if (at != null && isConnector(level.getBlockState(at))) {
            if (data.stored.connectors.add(key)) data.setDirty();
            return true;
        }
        if (at == null || data.stored.connectors.contains(key)) return false;
        BlockState state = connector();
        if (state == null) return false;
        level.setBlock(at, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        data.stored.connectors.add(key);
        data.setDirty();
        return true;
    }

    /** SU the grid wired to the pod's connector has to spare (0 when it has none, or its fuse is blown). */
    public static double spareSu(ServerLevel level, BlockPos pod) {
        BlockPos at = connectorSpot(level, pod);
        if (at == null || !(level.getBlockEntity(at) instanceof AbstractElectricalConnector connector)) return 0;
        PowerGrids.Grid grid = PowerGrids.of(connector);
        if (grid == null || grid.stats.tripped) return 0;
        return Math.max(0, grid.stats.capacity - grid.stats.demand);
    }

    /** True if the pod's grid has the megawatts it asks for to spare. */
    public static boolean powered(ServerLevel level, BlockPos pod, int mw) {
        return spareSu(level, pod) >= Power.su((double) mw) - 1e-6;
    }

    /** A few broken pieces of casing and scorched ground next to the pod, and its wire connector if it wants power. */
    public static void wreckage(ServerLevel level, BlockPos pod) {
        wreckage(level, pod, null);
    }

    /**
     * As above; with {@code only} (world generation), nothing outside that chunk is read or changed. A pod at the chunk's
     * edge may then get its connector later, when someone first tries to open it.
     */
    public static void wreckage(ServerLevel level, BlockPos pod, @Nullable ChunkPos only) {
        Block casing = BuiltInRegistries.BLOCK.getOptional(Identifier.parse("create:andesite_casing")).orElse(Blocks.IRON_BLOCK);
        long h = hash(pod);
        for (int i = 0; i < 6; i++) {
            h = h * 6364136223846793005L + 1442695040888963407L;
            int dx = (int) Long.remainderUnsigned(h >>> 16, 5) - 2, dz = (int) Long.remainderUnsigned(h >>> 24, 5) - 2;
            if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) continue;  // the cells round the pod stay clear for its connector
            BlockPos at = pod.offset(dx, 0, dz);
            if (!Collectibles.inside(only, at)) continue;
            // settle onto the ground within a couple of blocks, and never replace anything solid
            for (int drop = 0; drop < 3 && level.getBlockState(at.below()).canBeReplaced(); drop++) at = at.below();
            if (!level.getBlockState(at).canBeReplaced() || level.getBlockState(at.below()).canBeReplaced()) continue;
            BlockState piece = i % 3 == 0 ? Blocks.COARSE_DIRT.defaultBlockState() : i % 3 == 1 ? casing.defaultBlockState() : Blocks.IRON_BARS.defaultBlockState();
            if (i % 3 == 0) level.setBlock(at.below(), piece, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            else level.setBlock(at, piece, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        if (requirement(level, pod).kind() == Requirement.Kind.POWER) placeConnector(level, pod, only);
    }
}
