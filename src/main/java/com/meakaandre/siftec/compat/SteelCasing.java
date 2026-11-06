package com.meakaandre.siftec.compat;

import com.meakaandre.siftec.registry.ModBlocks;
import com.zurrtum.create.AllBlockEntityTypes;
import com.zurrtum.create.AllBlocks;
import com.zurrtum.create.content.decoration.encasing.EncasingRegistry;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * The Steel Casing does what Create's casings do: right-clicked onto a shaft, a cogwheel or a large cogwheel it encases
 * it (Create's {@code EncasingRegistry}; the steel-encased ones run on Create's own block entities, {@code
 * mixin.steel.SteelEncasedTypeMixin}), and it encases a chute as Create's industrial iron did ({@code
 * mixin.steel.ChuteSteelMixin}). Create's belts take only its own andesite and brass casings (a fixed list in Create's
 * belt block entity), so a belt is not encased in steel.
 */
public final class SteelCasing {
    private SteelCasing() {
    }

    public static void register() {
        EncasingRegistry.addVariant(AllBlocks.SHAFT, ModBlocks.STEEL_ENCASED_SHAFT.get());
        EncasingRegistry.addVariant(AllBlocks.COGWHEEL, ModBlocks.STEEL_ENCASED_COGWHEEL.get());
        EncasingRegistry.addVariant(AllBlocks.LARGE_COGWHEEL, ModBlocks.STEEL_ENCASED_LARGE_COGWHEEL.get());
    }

    /** The Create block entity a steel-encased block runs on, or null for any other block. */
    public static BlockEntityType<?> typeOf(Block block) {
        if (block == ModBlocks.STEEL_ENCASED_SHAFT.get()) return AllBlockEntityTypes.ENCASED_SHAFT;
        if (block == ModBlocks.STEEL_ENCASED_COGWHEEL.get()) return AllBlockEntityTypes.ENCASED_COGWHEEL;
        if (block == ModBlocks.STEEL_ENCASED_LARGE_COGWHEEL.get()) return AllBlockEntityTypes.ENCASED_LARGE_COGWHEEL;
        return null;
    }
}
