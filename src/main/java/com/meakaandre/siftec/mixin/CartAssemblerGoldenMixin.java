package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.cosmetic.GoldenCart;
import com.zurrtum.create.content.contraptions.mounted.CartAssemblerBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The Golden Cart Assembler skin: Create's Cart Assembler gets a "golden" state, off unless the Customizer turns it on. */
@Mixin(CartAssemblerBlock.class)
public abstract class CartAssemblerGoldenMixin extends BaseRailBlock {
    protected CartAssemblerGoldenMixin(boolean straight, Properties properties) {
        super(straight, properties);
    }

    @Inject(method = "createBlockStateDefinition", at = @At("TAIL"))
    private void siftec$golden(StateDefinition.Builder<Block, BlockState> builder, CallbackInfo ci) {
        builder.add(GoldenCart.GOLDEN);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void siftec$notGolden(Properties properties, CallbackInfo ci) {
        registerDefaultState(defaultBlockState().setValue(GoldenCart.GOLDEN, false));
    }
}
