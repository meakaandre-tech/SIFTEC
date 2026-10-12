package com.meakaandre.siftec.building;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * The Build Gun: Tab picks a tab, Ctrl + Scroll a building in it, right click builds at the hologram (R turns it,
 * F flips it, Ctrl locks it in place so WASD can nudge it a block at a time), left click cancels, and dismantle mode
 * takes a building down whole with a full refund. The keys are read by the client (client.BuildGunClient); using
 * it on a block is caught before the block can react (see {@link BuildGun#register}).
 */
public class BuildGunItem extends Item {
    public BuildGunItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (player instanceof ServerPlayer server) BuildGun.use(server);
        return InteractionResult.SUCCESS;
    }
}
