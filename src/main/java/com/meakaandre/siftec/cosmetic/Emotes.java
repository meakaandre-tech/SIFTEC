package com.meakaandre.siftec.cosmetic;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.sink.Shop;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Emotes (AWESOME Shop): {@code /emote <name>} plays one the company has bought (the Wave is free) for everyone nearby:
 * the player's arms move through it for three seconds ({@code client.CosmeticsClient}).
 */
public final class Emotes {
    public static final int TICKS = 60;

    private Emotes() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(EmotePayload.TYPE, EmotePayload.STREAM_CODEC);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(Commands.literal("emote")
            .then(Commands.argument("emote", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(Shop.emotes(), builder))
                .executes(context -> play(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "emote")) ? 1 : 0))));
    }

    /** Plays an emote if the player's company has it; false (and why, on screen) if not. */
    public static boolean play(ServerPlayer player, String emote) {
        if (!Shop.emotes().contains(emote)) {
            player.sendOverlayMessage(Component.translatable("siftec.emote.unknown", emote));
            return false;
        }
        Company company = Companies.of(player);
        if (!Shop.owns(player, company, "emote:" + emote)) {
            player.sendOverlayMessage(Component.translatable("siftec.emote.locked", Component.translatable("siftec.shop.entry.emote_" + emote)));
            return false;
        }
        EmotePayload payload = new EmotePayload(player.getId(), emote);
        ServerPlayNetworking.send(player, payload);
        for (ServerPlayer other : PlayerLookup.tracking(player)) if (other != player) ServerPlayNetworking.send(other, payload);
        return true;
    }
}
