package com.meakaandre.siftec.node;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

/** The rules for what a node is: which types a biome and distance allow, and how purity is drawn. */
final class NodeRules {
    /** Within this many blocks of world spawn, most nodes are impure. */
    private static final int NEAR = 1500;
    private static final int FAR = 5000;
    /** Chance of an impure and of a pure node near spawn, and how much each changes by far out. */
    private static final float IMPURE_NEAR = 0.5f, IMPURE_DROP = 0.3f, PURE_NEAR = 0.1f, PURE_RISE = 0.3f;

    private static final TagKey<Biome> COLD = biomeTag("is_cold"), DESERT = biomeTag("is_desert"), SWAMP = biomeTag("is_swamp");

    private NodeRules() {
    }

    private static TagKey<Biome> biomeTag(String path) {
        return TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("c", path));
    }

    // ---- types and purity

    /** Impure is the common one near spawn, pure the common one far out. */
    static Purity purity(long h, double distance) {
        float t = (float) Math.clamp((distance - NEAR) / (FAR - NEAR), 0, 1);
        float impure = IMPURE_NEAR - IMPURE_DROP * t;
        float pure = PURE_NEAR + PURE_RISE * t;
        float p = (NodeMap.mix(h) >>> 40) / (float) (1 << 24);
        return p < impure ? Purity.IMPURE : p < impure + pure ? Purity.PURE : Purity.NORMAL;
    }

    static boolean allowed(NodeType type, Holder<Biome> biome, double distance) {
        if (distance < type.minDistance) return false;
        return switch (type.where) {
            case ANY -> true;
            case HILLS -> biome.is(BiomeTags.IS_MOUNTAIN) || biome.is(BiomeTags.IS_HILL);
            case HOT -> biome.is(BiomeTags.IS_JUNGLE) || biome.is(BiomeTags.IS_SAVANNA) || biome.is(BiomeTags.IS_BADLANDS);
            case COLD -> biome.is(COLD) || biome.is(BiomeTags.IS_MOUNTAIN);
            case OILY -> biome.is(DESERT) || biome.is(SWAMP) || biome.is(BiomeTags.IS_BADLANDS) || biome.is(BiomeTags.IS_BEACH);
            case NETHER, SULFUR_CAVE -> false;
        };
    }
}
