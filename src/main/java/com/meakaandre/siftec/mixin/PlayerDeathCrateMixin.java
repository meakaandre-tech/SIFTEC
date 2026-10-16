package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.death.DeathCrates;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Where vanilla drops a dead player's inventory, everything they carry goes into a Death Crate instead
 * ({@link DeathCrates#pack}); the inventory is empty by the time vanilla would drop it. Curse of Vanishing items
 * vanish first, as in vanilla, and with keepInventory on nothing happens.
 */
@Mixin(Player.class)
public abstract class PlayerDeathCrateMixin {
    @Shadow
    protected abstract void destroyVanishingCursedItems();

    @Inject(method = "dropEquipment(Lnet/minecraft/server/level/ServerLevel;)V", at = @At("HEAD"))
    private void siftec$deathCrate(ServerLevel level, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player) || level.getGameRules().get(GameRules.KEEP_INVENTORY)) return;
        destroyVanishingCursedItems();
        DeathCrates.pack(player);
    }
}
