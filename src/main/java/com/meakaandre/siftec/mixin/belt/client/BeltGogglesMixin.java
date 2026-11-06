package com.meakaandre.siftec.mixin.belt.client;

import com.meakaandre.siftec.belt.BeltAttachments;
import com.meakaandre.siftec.belt.BeltLook;
import com.meakaandre.siftec.tweak.SelfMovingBelts;
import com.zurrtum.create.api.behaviour.BlockEntityBehaviour;
import com.zurrtum.create.client.api.goggles.IHaveGoggleInformation;
import com.zurrtum.create.client.foundation.blockEntity.behaviour.tooltip.KineticTooltipBehaviour;
import com.zurrtum.create.client.foundation.blockEntity.behaviour.tooltip.TooltipBehaviour;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * The goggles on a belt: its Mk and items a minute (the milestone speed), then whatever the built-in funnel or tunnel
 * shows on Create's own block (a brass tunnel's distribution, say).
 */
@Mixin(value = KineticTooltipBehaviour.class, remap = false)
public abstract class BeltGogglesMixin {
    @Inject(method = "addToGoggleTooltip", at = @At("RETURN"), cancellable = true)
    private void siftec$belt(List<Component> tooltip, boolean sneaking, CallbackInfoReturnable<Boolean> cir) {
        if (!(((BlockEntityBehaviour<?>) (Object) this).blockEntity instanceof BeltBlockEntity belt)) return;
        BeltBlockEntity controller = belt.isController() ? belt : belt.getControllerBE();
        int perMinute = controller == null ? 0 : Math.round(Math.abs(SelfMovingBelts.speed(controller)) * SelfMovingBelts.ITEMS_PER_MINUTE_PER_RPM);
        tooltip.add(Component.literal("    ").append(perMinute > 0
            ? Component.translatable("siftec.belt.goggles", BeltLook.mk(perMinute), perMinute) : Component.translatable("siftec.belt.goggles.still"))
            .withStyle(ChatFormatting.GRAY));
        SmartBlockEntity a = BeltAttachments.of(belt);
        TooltipBehaviour<?> inner = a == null ? null : a.getBehaviour(TooltipBehaviour.TYPE);
        if (inner instanceof IHaveGoggleInformation goggles) BeltAttachments.run(() -> goggles.addToGoggleTooltip(tooltip, sneaking));
        cir.setReturnValue(true);
    }
}
