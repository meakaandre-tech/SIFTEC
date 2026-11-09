package com.meakaandre.siftec.mixin.belt.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.meakaandre.siftec.client.belt.PonderBuiltIn;
import com.mojang.blaze3d.vertex.PoseStack;
import com.zurrtum.create.client.flywheel.lib.model.baked.ModelConsumer;
import com.zurrtum.create.client.ponder.api.level.PonderLevel;
import com.zurrtum.create.client.ponder.foundation.element.WorldSectionElementImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Ponder draws Create's funnels and tunnels built into the belt (see {@link PonderBuiltIn}): a funnel or tunnel standing
 * on a belt goes into the belt's block, its block model and its block entity's renderer alike, and the belt under it
 * loses the casing Create gives a belt under a tunnel.
 */
@Mixin(value = WorldSectionElementImpl.class, remap = false)
public abstract class PonderBuiltInMixin {
    @WrapOperation(method = "buildStructureBuffer", at = @At(value = "INVOKE",
        target = "Lcom/zurrtum/create/client/flywheel/lib/model/baked/ModelConsumer;tesselateBlock(FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V"))
    private void siftec$builtInModel(ModelConsumer consumer, float x, float y, float z, BlockAndTintGetter getter, BlockPos pos, BlockState state,
                                     BlockStateModel model, long seed, Operation<Void> original) {
        if (getter instanceof Level level) {
            if (PonderBuiltIn.lowered(level, pos)) {
                original.call(consumer, x, y - 1, z, getter, pos, state, model, seed);
                return;
            }
            BlockState drawn = PonderBuiltIn.drawn(level, pos, state);
            if (drawn != state) {
                original.call(consumer, x, y, z, getter, pos, drawn, Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(drawn), seed);
                return;
            }
        }
        original.call(consumer, x, y, z, getter, pos, state, model, seed);
    }

    @WrapOperation(method = "renderBlockEntities", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
    private void siftec$builtInRenderer(PoseStack ms, float x, float y, float z, Operation<Void> original,
                                        @Local(argsOnly = true) PonderLevel level, @Local BlockEntity be) {
        original.call(ms, x, PonderBuiltIn.lowered(level, be.getBlockPos()) ? y - 1 : y, z);
    }
}
