package com.meakaandre.siftec.cosmetic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** A Shop sign's text: a sign's, under a type of its own so it is drawn as a Shop sign. */
public class ShopSignBlockEntity extends SignBlockEntity {
    @SuppressWarnings("unchecked")
    public ShopSignBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super((BlockEntityType<? extends SignBlockEntity>) type, pos, state);
    }
}
