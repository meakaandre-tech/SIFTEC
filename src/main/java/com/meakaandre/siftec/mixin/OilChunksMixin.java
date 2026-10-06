package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.compat.OilNodes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Create: Diesel Generators asks how much oil a chunk has; the pack answers from its oil nodes. */
@Pseudo
@Mixin(targets = "com.jesz.createdieselgenerators.world.OilChunksSavedData", remap = false)
public abstract class OilChunksMixin {
    @Inject(method = "getChunkOilAmount(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;)I", at = @At("HEAD"), cancellable = true)
    private static void siftec$oilFromNodes(ServerLevel level, ChunkPos chunk, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(OilNodes.amount(level, chunk));
    }

    @Inject(method = "setChunkOilAmount(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;I)V", at = @At("HEAD"), cancellable = true)
    private static void siftec$neverRunsDry(ServerLevel level, ChunkPos chunk, int amount, CallbackInfo ci) {
        ci.cancel();
    }
}
