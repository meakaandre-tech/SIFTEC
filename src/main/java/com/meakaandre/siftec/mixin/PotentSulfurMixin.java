package com.meakaandre.siftec.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.PotentSulfurBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Geysers no longer keep a steady beat. Vanilla's countdown ticker (the server ticker, run once a second, so the
 * countdown is in seconds) sets a resting time from a random fixed per position: 15 to 30 seconds (10 more per
 * extra block of water), the same every time. Here each rest is rolled again: 18 to 66 seconds, most near the
 * middle, so with the eruption itself (1 to 3 seconds) one eruption follows another every 20 to 70 seconds, about
 * 45 on average. Eruptions, and everything else about the geyser, stay vanilla; nothing new is saved.
 *
 * The ticker is the lambda vanilla stores in {@code SERVER_WAITING_COUNTDOWN_TICKER} ({@code lambda$static$4} in
 * 26.3). Its first write to {@code waitingCountdown} is the one in the branch for a resting geyser.
 */
@Mixin(PotentSulfurBlockEntity.class)
public abstract class PotentSulfurMixin {
    @Inject(method = "lambda$static$4", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/block/entity/PotentSulfurBlockEntity;waitingCountdown:I",
        opcode = Opcodes.PUTFIELD, ordinal = 0, shift = At.Shift.AFTER))
    private static void siftec$unevenRest(Level level, BlockPos pos, BlockState state, PotentSulfurBlockEntity geyser, CallbackInfo ci) {
        // two rolls added together: most rests land near the middle, a few near either end
        geyser.waitingCountdown = 18 + level.getRandom().nextInt(25) + level.getRandom().nextInt(25);
    }
}
