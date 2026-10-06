package com.meakaandre.siftec.geyser;

import com.meakaandre.siftec.backpack.Backpack;
import com.meakaandre.siftec.registry.ModItems;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Toxic Residue gives off radiation: carrying it, or standing within a few blocks of some lying on the
 * ground, hurts. Only a Hazmat Suit with an Iodine Infused Filter shields it.
 */
public final class Radiation {
    private static final double RANGE = 4;

    private Radiation() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 40 != 0) return;
            Item residue = ModItems.TOXIC_RESIDUE.get();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.hasInfiniteMaterials() || player.isSpectator()) continue;
                boolean exposed = player.getInventory().contains(stack -> stack.is(residue));
                if (!exposed) {
                    for (ItemStack stack : player.getAttachedOrCreate(Backpack.CONTENTS).items) {
                        if (stack.is(residue)) exposed = true;
                    }
                }
                ServerLevel level = player.level();
                if (!exposed) {
                    exposed = !level.getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(RANGE), e -> e.getItem().is(residue)).isEmpty();
                }
                if (exposed && !com.meakaandre.siftec.equip.Equipment.shieldsRadiation(player)) player.hurtServer(level, level.damageSources().magic(), 1.0f);
            }
        });
    }
}
