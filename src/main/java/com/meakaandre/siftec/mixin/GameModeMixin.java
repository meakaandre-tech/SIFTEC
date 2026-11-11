package com.meakaandre.siftec.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.meakaandre.siftec.owner.Ownership;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Whatever a player's click builds (a block, a belt, a whole multi-block) is marked as their company's. The whole method
 * is wrapped, so the player stops acting however the click ends: a hook that cancels it early (Create's right-click
 * handlers, a refused click on a building) returns past any {@code RETURN} injection, and the player would stay acting
 * for whatever is placed next by anything else.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class GameModeMixin {
    @WrapMethod(method = "useItemOn")
    private InteractionResult siftec$acting(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hit,
                                            Operation<InteractionResult> original) {
        Ownership.acting(player);
        try {
            return original.call(player, level, stack, hand, hit);
        } finally {
            Ownership.acting(null);
        }
    }
}
