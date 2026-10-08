package com.meakaandre.siftec.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.meakaandre.siftec.owner.Ownership;
import com.zurrtum.create.content.kinetics.deployer.ManualApplicationHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

/** Putting a casing on by hand (item application) follows the player's company's unlocks, as the Deployer does. */
@Mixin(value = ManualApplicationHelper.class, remap = false)
public abstract class ManualApplicationMixin {
    @WrapMethod(method = "manualApplicationRecipesApplyInWorld")
    private static InteractionResult siftec$asPlayer(Level level, Player player, ItemStack held, InteractionHand hand, BlockHitResult hit, BlockPos pos,
                                                     Operation<InteractionResult> original) {
        ServerPlayer before = Ownership.asking(player instanceof ServerPlayer server ? server : null);
        try {
            return original.call(level, player, held, hand, hit, pos);
        } finally {
            Ownership.asking(before);
        }
    }
}
