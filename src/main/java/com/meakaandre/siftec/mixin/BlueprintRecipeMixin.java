package com.meakaandre.siftec.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.meakaandre.siftec.owner.Ownership;
import com.zurrtum.create.content.equipment.blueprint.BlueprintEntity;
import com.zurrtum.create.infrastructure.packet.s2c.BlueprintPreviewPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

/** Create's Crafting Blueprint crafts from the player's inventory on a click: only what the player's company has unlocked. */
@Mixin(value = BlueprintEntity.class, remap = false)
public abstract class BlueprintRecipeMixin {
    @WrapMethod(method = "interact")
    private InteractionResult siftec$asPlayer(Player player, InteractionHand hand, Vec3 vec, Operation<InteractionResult> original) {
        ServerPlayer before = Ownership.asking(player instanceof ServerPlayer server ? server : null);
        try {
            return original.call(player, hand, vec);
        } finally {
            Ownership.asking(before);
        }
    }

    @WrapMethod(method = "createPreview")
    private static BlueprintPreviewPacket siftec$previewAsPlayer(BlueprintEntity be, int index, ServerPlayer player, boolean sneaking,
                                                                 Operation<BlueprintPreviewPacket> original) {
        ServerPlayer before = Ownership.asking(player);
        try {
            return original.call(be, index, player, sneaking);
        } finally {
            Ownership.asking(before);
        }
    }
}
