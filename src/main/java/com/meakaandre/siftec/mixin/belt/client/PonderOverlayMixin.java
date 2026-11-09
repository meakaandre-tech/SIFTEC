package com.meakaandre.siftec.mixin.belt.client;

import com.meakaandre.siftec.client.belt.PonderBuiltIn;
import com.zurrtum.create.client.ponder.foundation.PonderScene;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Ponder's text and input windows point at a funnel or tunnel where it is drawn: one built into a belt (see
 * {@link PonderBuiltIn}) is a block lower than where the scene keeps it, so a point in its cell moves down with it.
 */
@Mixin(value = PonderScene.SceneTransform.class, remap = false)
public abstract class PonderOverlayMixin {
    @Shadow
    @Final
    PonderScene this$0;

    @ModifyVariable(method = "sceneToScreen", at = @At("HEAD"), argsOnly = true)
    private Vec3 siftec$builtIn(Vec3 point) {
        return PonderBuiltIn.anchor(this$0.getLevel(), point);
    }
}
