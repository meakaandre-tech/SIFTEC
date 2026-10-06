package com.meakaandre.siftec.collect;

import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.node.NodeSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** Crash sites: what a cargo pod wants before it opens, and the wreckage lying round it. */
public final class CrashSites {
    /** Pods within this distance of world spawn open freely. */
    private static final int FREE = 400, STEP = 500;
    /** What pods ask for, easiest first. A pod picks from the first few; further out it can pick from more. */
    private static final String[][] WANTS = {
        {"siftec:iron_rod", "30"}, {"create:iron_sheet", "30"}, {"siftec:wire", "50"}, {"siftec:screw", "50"}, {"siftec:rotor", "20"},
        {"siftec:reinforced_iron_plate", "15"}, {"siftec:cable", "50"}, {"siftec:modular_frame", "10"}, {"siftec:steel_beam", "30"},
        {"siftec:steel_pipe", "30"}, {"siftec:stator", "10"}, {"siftec:motor", "5"}, {"siftec:circuit_board", "10"}, {"siftec:computer", "3"},
        {"siftec:heavy_modular_frame", "2"}, {"siftec:radio_control_unit", "1"}};

    private CrashSites() {
    }

    private static long hash(BlockPos pos) {
        long v = pos.getX() * 0x9E3779B97F4A7C15L ^ pos.getZ() * 0xC2B2AE3D27D4EB4FL;
        v ^= v >>> 31;
        v *= 0xff51afd7ed558ccdL;
        return v ^ (v >>> 33);
    }

    /** The parts this pod wants, or null if it opens freely. Worked out from where it is, so it never changes. */
    public static Milestone.@Nullable Cost requirement(ServerLevel level, BlockPos pos) {
        NodeSavedData data = NodeSavedData.get(level.getServer());
        double distance = Math.sqrt(Math.pow(pos.getX() - data.originX(), 2) + Math.pow(pos.getZ() - data.originZ(), 2));
        long h = hash(pos);
        if (distance < FREE || Long.remainderUnsigned(h, 4) == 0) return null;
        int reach = Math.min(WANTS.length, 3 + (int) ((distance - FREE) / STEP) * 2);
        String[] want = WANTS[(int) Long.remainderUnsigned(h >>> 8, reach)];
        return new Milestone.Cost(want[0], Integer.parseInt(want[1]));
    }

    /** One pod in five that landed well away from spawn also carries a Somersloop. */
    public static boolean hasSomersloop(ServerLevel level, BlockPos pos) {
        NodeSavedData data = NodeSavedData.get(level.getServer());
        double distance = Math.sqrt(Math.pow(pos.getX() - data.originX(), 2) + Math.pow(pos.getZ() - data.originZ(), 2));
        return distance >= 1000 && Long.remainderUnsigned(hash(pos) >>> 20, 5) == 0;
    }

    /** A few broken pieces of casing and scorched ground next to the pod. */
    public static void wreckage(ServerLevel level, BlockPos pod) {
        Block casing = BuiltInRegistries.BLOCK.getOptional(Identifier.parse("create:andesite_casing")).orElse(Blocks.IRON_BLOCK);
        long h = hash(pod);
        for (int i = 0; i < 6; i++) {
            h = h * 6364136223846793005L + 1442695040888963407L;
            int dx = (int) Long.remainderUnsigned(h >>> 16, 5) - 2, dz = (int) Long.remainderUnsigned(h >>> 24, 5) - 2;
            if (dx == 0 && dz == 0) continue;
            BlockPos at = pod.offset(dx, 0, dz);
            // settle onto the ground within a couple of blocks, and never replace anything solid
            for (int drop = 0; drop < 3 && level.getBlockState(at.below()).canBeReplaced(); drop++) at = at.below();
            if (!level.getBlockState(at).canBeReplaced() || level.getBlockState(at.below()).canBeReplaced()) continue;
            BlockState piece = i % 3 == 0 ? Blocks.COARSE_DIRT.defaultBlockState() : i % 3 == 1 ? casing.defaultBlockState() : Blocks.IRON_BARS.defaultBlockState();
            if (i % 3 == 0) level.setBlock(at.below(), piece, Block.UPDATE_CLIENTS);
            else level.setBlock(at, piece, Block.UPDATE_CLIENTS);
        }
    }
}
