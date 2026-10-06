package com.meakaandre.siftec.equip;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Landing pad: whatever lands on it takes no fall damage, however far it fell. */
public class LandingPadBlock extends Block {
    public LandingPadBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void fallOn(Level level, BlockState state, BlockPos pos, Entity entity, double fallDistance) {
        entity.resetFallDistance();
    }
}
