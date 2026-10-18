package com.meakaandre.siftec.cosmetic;

import com.zurrtum.create.AllBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * The Golden Cart Assembler skin (AWESOME Shop): Create's Cart Assembler gets a "golden" state ({@code
 * mixin.CartAssemblerGoldenMixin}) the Customizer switches; it is drawn in gold ({@code client.CosmeticsClient}) and works
 * as any Cart Assembler.
 */
public final class GoldenCart {
    public static final BooleanProperty GOLDEN = BooleanProperty.create("golden");

    private GoldenCart() {
    }

    public static boolean is(BlockState state) {
        return state.is(AllBlocks.CART_ASSEMBLER) && state.hasProperty(GOLDEN);
    }

    /** Turns a Cart Assembler golden, or back; false if there is none there. */
    public static boolean toggle(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!is(state)) return false;
        level.setBlock(pos, state.setValue(GOLDEN, !state.getValue(GOLDEN)), Block.UPDATE_ALL);
        return true;
    }
}
