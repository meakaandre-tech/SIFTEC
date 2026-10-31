package com.meakaandre.siftec.building;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * The Build Gun, worked like Satisfactory's: Q opens the build menu, 1 to 0 pick from the build hotbar, left click
 * builds at the hologram (scroll turns it, Alt + R mirrors it, H locks it for the arrow keys and Page Up/Down to nudge),
 * right click cancels, and F is dismantle mode, where left click held for a second takes a building down whole with a
 * full refund (Ctrl selects up to 50). The keys are read by the client (client.BuildGunClient), which sends the server
 * what to build or take down ({@link BuildGunPayloads}); using the item itself does nothing.
 */
public class BuildGunItem extends Item {
    public BuildGunItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }
}
