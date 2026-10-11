package com.meakaandre.siftec.prefab;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The shell of a HUB or Wormhole Gateway: plain blocks dressed in Create and vanilla textures (casings, concrete,
 * glass, lamps). A "step" is half a block high, so the entrance can be walked up.
 */
public class PrefabBlock extends Block implements PrefabPart {
    private static final VoxelShape STEP = Block.box(0, 0, 0, 16, 8, 16);
    private final boolean step;

    public PrefabBlock(boolean step, Properties properties) {
        super(properties);
        this.step = step;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return step ? STEP : super.getShape(state, level, pos, context);
    }
}
