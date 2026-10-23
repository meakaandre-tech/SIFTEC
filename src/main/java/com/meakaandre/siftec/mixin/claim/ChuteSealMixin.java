package com.meakaandre.siftec.mixin.claim;

import com.meakaandre.siftec.building.BuildingProtection;
import com.meakaandre.siftec.claim.Claims;
import com.zurrtum.create.content.logistics.chute.ChuteBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chutes and Smart Chutes (Smart Chutes are Chutes underneath) reach a building only at its ports, as funnels and
 * hoppers do ({@link BuildingProtection#sealed}): from outside, a chute takes nothing out of and puts nothing into a building's
 * machines or controller, pulls nothing off a belt inside it, and drops nothing onto a block of it. Nor does one reach
 * across someone else's claim border.
 */
@Mixin(ChuteBlockEntity.class)
public abstract class ChuteSealMixin {
    private boolean siftec$sealed(BlockPos target) {
        ChuteBlockEntity self = (ChuteBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide()) return false;
        BlockPos from = self.getBlockPos();
        return BuildingProtection.sealed(level, from, target) || Claims.crosses(level, from, target);
    }

    /** No inventory of a building's machine (or across a claim border) above or below it. */
    @Inject(method = "grabCapability", at = @At("HEAD"), cancellable = true)
    private void siftec$grab(Direction side, CallbackInfoReturnable<Container> cir) {
        ChuteBlockEntity self = (ChuteBlockEntity) (Object) this;
        if (siftec$sealed(self.getBlockPos().relative(side))) cir.setReturnValue(null);
    }

    /** Nothing pulled off a belt that belongs to a building. */
    @Inject(method = "extractFromBelt", at = @At("HEAD"), cancellable = true)
    private void siftec$belt(float itemSpeed, CallbackInfo ci) {
        ChuteBlockEntity self = (ChuteBlockEntity) (Object) this;
        if (siftec$sealed(self.getBlockPos().below())) ci.cancel();
    }

    /** Nothing dropped down onto a block of a building other than its port. */
    @Inject(method = "handleDownwardOutput", at = @At("HEAD"), cancellable = true)
    private void siftec$down(boolean simulate, CallbackInfoReturnable<Boolean> cir) {
        ChuteBlockEntity self = (ChuteBlockEntity) (Object) this;
        if (siftec$sealed(self.getBlockPos().below())) cir.setReturnValue(false);
    }

    /** Nor sent up into one. */
    @Inject(method = "handleUpwardOutput", at = @At("HEAD"), cancellable = true)
    private void siftec$up(boolean simulate, CallbackInfoReturnable<Boolean> cir) {
        ChuteBlockEntity self = (ChuteBlockEntity) (Object) this;
        if (siftec$sealed(self.getBlockPos().above())) cir.setReturnValue(false);
    }
}
