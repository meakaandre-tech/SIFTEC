package com.meakaandre.siftec.hub;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The HUB has to be a real building. A box is marked round it with the HUB Planner; to work on a tier, the
 * box must hold 50 x (tier + 1) blocks from that tier's material families (25 more per extra company member),
 * at least 50 of them from the newest family the tier allows, and from Tier 1 two walls and a roof.
 * Carried over from the old Tweaker mod.
 */
public final class HubBuilding {
    public static final int NEW_MATERIAL = 50, PER_TIER = 50, PER_MEMBER = 25;
    private static final int SHELTER_FROM_TIER = 1, SHELL = 2, VOLUME_FACTOR = 7;
    /** The longest a side of the box may be, across and up, so a thin box cannot reach out over many chunks. */
    public static final int MAX_SIDE = 48, MAX_HEIGHT = 32;
    private static final double SHELTER_COVERAGE = 0.6;

    public record Family(String key, int fromTier, Predicate<BlockState> test) {
    }

    private static boolean shape(Block b) {
        return b instanceof StairBlock || b instanceof SlabBlock || b instanceof FenceBlock || b instanceof FenceGateBlock
            || b instanceof WallBlock || b instanceof DoorBlock || b instanceof TrapDoorBlock;
    }

    private static Predicate<BlockState> ids(String... ids) {
        return state -> {
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            for (String want : ids) if (id.equals(want)) return true;
            return false;
        };
    }

    private static boolean stoneOrWood(BlockState state) {
        Block b = state.getBlock();
        if (shape(b)) return true;
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS)) return true;
        Identifier id = BuiltInRegistries.BLOCK.getKey(b);
        if (id.getNamespace().equals("twigs")) return true;
        if (!id.getNamespace().equals("minecraft")) return false;
        String path = id.getPath();
        for (String prefix : new String[]{"stone_brick", "mossy_stone_brick", "cracked_stone_brick", "chiseled_stone_brick", "smooth_stone",
            "polished_andesite", "polished_diorite", "polished_granite", "polished_deepslate", "deepslate_brick", "deepslate_tile",
            "cracked_deepslate", "chiseled_deepslate"}) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean copper(BlockState state) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id.toString().equals("create:copper_casing")) return true;
        return id.getNamespace().equals("minecraft") && !shape(state.getBlock())
            && (id.getPath().endsWith("copper_block") || id.getPath().endsWith("cut_copper") || id.getPath().endsWith("chiseled_copper") || id.getPath().equals("waxed_copper"));
    }

    /**
     * Oldest first. A family counts from the tier it is introduced at. These are the old Tweaker mod's families
     * with copper added ahead of steel.
     */
    public static final List<Family> FAMILIES = List.of(
        new Family("stone_wood", 0, HubBuilding::stoneOrWood),
        new Family("andesite", 1, ids("create:andesite_casing", "create:andesite_alloy_block")),
        new Family("copper", 2, HubBuilding::copper),
        new Family("steel", 4, ids("siftec:steel_casing", "cgs:steel_block")),
        new Family("brass", 5, ids("create:brass_casing", "create:brass_block")));

    public static final class Result {
        public final int[] counts = new int[FAMILIES.size()];
        public int walls;
        public boolean roof, hubInside, tooBig, tooLong;
        /** Chunks inside the box that were not loaded and so not counted. */
        public int unloaded;
        public long volume;
        public int members = 1;

        public int required(int tier) {
            return PER_TIER * (tier + 1) + PER_MEMBER * Math.max(0, members - 1);
        }

        public int total(int tier) {
            int total = 0;
            for (int i = 0; i < FAMILIES.size(); i++) if (FAMILIES.get(i).fromTier() <= tier) total += counts[i];
            return total;
        }

        public boolean meets(int tier) {
            if (!hubInside || tooBig || tooLong) return false;
            boolean shelter = tier < SHELTER_FROM_TIER || (walls >= 2 && roof);
            return shelter && total(tier) >= required(tier) && counts[newestFamily(tier)] >= NEW_MATERIAL;
        }

        /** The highest tier this building is good for, or -1. */
        public int builtTier() {
            for (int tier = Milestones.TIERS - 1; tier >= 0; tier--) if (meets(tier)) return tier;
            return -1;
        }

        /** What the building has, and what it still lacks for the tier. */
        public List<Component> report(int tier) {
            List<Component> out = new ArrayList<>();
            if (!hubInside) {
                out.add(Component.translatable("siftec.building.hub_outside"));
                return out;
            }
            if (tooLong) {
                out.add(Component.translatableWithFallback("siftec.building.too_long", "The marked area is too long: at most %s blocks across and %s high", MAX_SIDE, MAX_HEIGHT));
                return out;
            }
            if (tooBig) {
                out.add(Component.translatable("siftec.building.too_big", volume, (long) VOLUME_FACTOR * required(Milestones.TIERS - 1)));
                return out;
            }
            int newest = newestFamily(tier);
            out.add(Component.translatable("siftec.building.blocks", tier, total(tier), required(tier)));
            out.add(Component.translatable("siftec.building.newest", Component.translatable("siftec.building.family." + FAMILIES.get(newest).key()), counts[newest], NEW_MATERIAL));
            if (tier >= SHELTER_FROM_TIER) out.add(Component.translatable("siftec.building.shelter", walls, Component.translatable(roof ? "siftec.boost.yes" : "siftec.boost.no")));
            if (unloaded > 0) out.add(Component.translatableWithFallback("siftec.building.unloaded", "%s chunks of the marked area are not loaded and were not counted", unloaded));
            out.add(Component.translatable(meets(tier) ? "siftec.building.ok" : "siftec.building.not_ok", tier));
            return out;
        }
    }

    private HubBuilding() {
    }

    public static int newestFamily(int tier) {
        int newest = 0;
        for (int i = 0; i < FAMILIES.size(); i++) if (FAMILIES.get(i).fromTier() <= tier) newest = i;
        return newest;
    }

    /** Slabs, doors and trapdoors count as half a block. */
    private static int halves(BlockState state) {
        if (state.getBlock() instanceof SlabBlock) return state.getValue(SlabBlock.TYPE) == SlabType.DOUBLE ? 2 : 1;
        if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock) return 1;
        return 2;
    }

    public static Result scan(ServerLevel level, BlockPos hub, BlockPos min, BlockPos max, int members) {
        Result result = new Result();
        result.members = members;
        result.hubInside = hub.getX() >= min.getX() && hub.getX() <= max.getX() && hub.getY() >= min.getY() && hub.getY() <= max.getY()
            && hub.getZ() >= min.getZ() && hub.getZ() <= max.getZ();
        result.volume = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
        result.tooBig = result.volume > (long) VOLUME_FACTOR * result.required(Milestones.TIERS - 1);
        result.tooLong = max.getX() - min.getX() + 1 > MAX_SIDE || max.getZ() - min.getZ() + 1 > MAX_SIDE || max.getY() - min.getY() + 1 > MAX_HEIGHT;
        if (!result.hubInside || result.tooBig || result.tooLong) return result;
        // only what is loaded is looked at: measuring must never load or generate chunks
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) if (!level.hasChunk(cx, cz)) result.unloaded++;
        }
        int[] halves = new int[FAMILIES.size()];
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (result.unloaded > 0 && !level.isLoaded(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            for (int i = FAMILIES.size() - 1; i >= 0; i--) {
                if (FAMILIES.get(i).test().test(state)) {
                    halves[i] += halves(state);
                    break;
                }
            }
        }
        for (int i = 0; i < halves.length; i++) result.counts[i] = halves[i] / 2;
        shelter(level, hub, min, max, result);
        return result;
    }

    private static boolean solid(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /**
     * Walls and roof, from any solid blocks in the outer two layers of the box. A side is a wall when most
     * spots on it, from the HUB's level up, have something solid; the roof is there when most columns do.
     */
    private static void shelter(ServerLevel level, BlockPos hub, BlockPos min, BlockPos max, Result result) {
        int y0 = hub.getY(), y1 = max.getY();
        for (int side = 0; side < 4; side++) {
            boolean alongX = side < 2;
            int uFrom = alongX ? min.getZ() : min.getX(), uTo = alongX ? max.getZ() : max.getX();
            int edge = side == 0 ? min.getX() : side == 1 ? max.getX() : side == 2 ? min.getZ() : max.getZ();
            int step = side == 0 || side == 2 ? 1 : -1;
            int limit = alongX ? hub.getX() : hub.getZ();
            int covered = 0, total = 0;
            for (int y = y0; y < y1; y++) {
                for (int u = uFrom; u <= uTo; u++) {
                    total++;
                    for (int k = 0; k < SHELL; k++) {
                        int v = edge + k * step;
                        if (step > 0 ? v >= limit : v <= limit) break;
                        if (solid(level, alongX ? new BlockPos(v, y, u) : new BlockPos(u, y, v))) {
                            covered++;
                            break;
                        }
                    }
                }
            }
            if (total > 0 && covered >= total * SHELTER_COVERAGE) result.walls++;
        }
        int columns = 0, roofed = 0;
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                columns++;
                for (int k = 0; k < SHELL; k++) {
                    int y = y1 - k;
                    if (y <= y0) break;
                    if (solid(level, new BlockPos(x, y, z))) {
                        roofed++;
                        break;
                    }
                }
            }
        }
        result.roof = columns > 0 && y1 > y0 && roofed >= columns * SHELTER_COVERAGE;
    }
}
