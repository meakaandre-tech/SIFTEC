package com.meakaandre.siftec.company;

import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.tweak.SpeedCap;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** Looking companies up, the /company command, and the starting HUB. */
public final class Companies {
    private Companies() {
    }

    public static void register() {
        ServerPlayerEvents.JOIN.register(player -> {
            CompanyData data = CompanyData.get(player.level().getServer());
            of(player);
            SpeedCap.recompute(player.level().getServer());
            if (data.gotHub().add(player.getUUID().toString())) {
                data.setDirty();
                player.getInventory().placeItemBackInInventory(new ItemStack(ModBlocks.HUB.get()), Prediction.SERVER_ONLY);
                player.getInventory().placeItemBackInInventory(new ItemStack(com.meakaandre.siftec.registry.ModItems.HUB_PLANNER.get()), Prediction.SERVER_ONLY);
            }
        });
        // once a second: finish any research whose time is up
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 != 0) return;
            long now = server.overworld().getGameTime();
            for (Company company : new java.util.ArrayList<>(CompanyData.get(server).companies().values())) {
                if (company.research.isEmpty() || now < company.researchEnd) continue;
                if (company.research.equals(com.meakaandre.siftec.hub.Alternates.RESEARCH)) {
                    company.research = "";
                    com.meakaandre.siftec.hub.Alternates.makeOffer(server, company);
                    continue;
                }
                Milestone m = Milestones.get(company.research);
                company.research = "";
                if (m != null) complete(server, company, m, Component.literal(company.name));
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("company")
                .executes(context -> info(context.getSource()))
                .then(Commands.literal("info").executes(context -> info(context.getSource())))
                .then(Commands.literal("rename").then(Commands.argument("name", StringArgumentType.greedyString())
                    .executes(context -> rename(context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("invite").then(Commands.argument("player", EntityArgument.player())
                    .executes(context -> invite(context.getSource(), EntityArgument.getPlayer(context, "player")))))
                .then(Commands.literal("accept").then(Commands.argument("player", EntityArgument.player())
                    .executes(context -> accept(context.getSource(), EntityArgument.getPlayer(context, "player")))))
                .then(Commands.literal("leave").executes(context -> leave(context.getSource())))
        ));
        // the cloud inventory can be opened from anywhere
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("depot").executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                com.meakaandre.siftec.depot.DepotMenu.open(player, of(player));
                return 1;
            })
        ));
    }

    /** The player's company; a one-person company is made the first time it is asked for. */
    public static Company of(ServerPlayer player) {
        CompanyData data = CompanyData.get(player.level().getServer());
        Company company = data.ofMember(player.getUUID().toString());
        if (company == null) company = create(data, player);
        return company;
    }

    /** Null on the client, where company state is not known. */
    public static Company of(Player player) {
        return player instanceof ServerPlayer server ? of(server) : null;
    }

    private static Company create(CompanyData data, ServerPlayer player) {
        Company company = new Company();
        company.name = player.getGameProfile().name() + "'s Company";
        company.members.add(player.getUUID().toString());
        data.companies().put(company.id, company);
        data.setDirty();
        return company;
    }

    public static void save(MinecraftServer server) {
        CompanyData.get(server).setDirty();
    }

    public static void tell(MinecraftServer server, Company company, Component message) {
        for (String member : company.members) {
            ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(member));
            if (player != null) player.sendSystemMessage(message);
        }
    }

    /** Marks a milestone done, starts the HUB lock, and tells the company. */
    public static void complete(MinecraftServer server, Company company, Milestone m, Component who) {
        company.done.add(m.id());
        company.paid.remove(m.id());
        if (company.active.equals(m.id())) company.active = "";
        if (!m.isPhase() && !m.isResearch() && m.seconds() > 0) {
            company.lockUntil = server.overworld().getGameTime() + m.seconds() * 20L;
        }
        save(server);
        SpeedCap.recompute(server);
        tell(server, company, Component.translatable("siftec.hub.complete", who, m.name()).withStyle(ChatFormatting.GOLD));
        tell(server, company, Component.translatable("siftec.hub.unlocks", m.unlockText()).withStyle(ChatFormatting.GRAY));
    }

    private static int info(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Company company = of(player);
        MinecraftServer server = source.getServer();
        StringBuilder names = new StringBuilder();
        for (String member : company.members) {
            ServerPlayer online = server.getPlayerList().getPlayer(UUID.fromString(member));
            if (names.length() > 0) names.append(", ");
            names.append(online != null ? online.getGameProfile().name() : member.substring(0, 8));
        }
        int finished = 0;
        for (Milestone m : Milestones.all()) if (company.has(m.id())) finished++;
        String summary = names + " | " + finished + " milestones | costs x" + company.costMultiplier();
        source.sendSuccess(() -> Component.translatable("siftec.company.info", company.name, summary), false);
        return 1;
    }

    private static int rename(CommandSourceStack source, String name) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Company company = of(source.getPlayerOrException());
        company.name = name.length() > 32 ? name.substring(0, 32) : name;
        save(source.getServer());
        tell(source.getServer(), company, Component.translatable("siftec.company.renamed", company.name));
        return 1;
    }

    private static int invite(CommandSourceStack source, ServerPlayer target) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Company company = of(player);
        company.invites.add(target.getUUID().toString());
        save(source.getServer());
        String inviter = player.getGameProfile().name();
        source.sendSuccess(() -> Component.translatable("siftec.company.invited", target.getGameProfile().name(), inviter), false);
        target.sendSystemMessage(Component.translatable("siftec.company.invite", inviter, company.name, inviter).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    private static int accept(CommandSourceStack source, ServerPlayer inviter) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        MinecraftServer server = source.getServer();
        CompanyData data = CompanyData.get(server);
        Company target = of(inviter);
        String me = player.getUUID().toString();
        if (target.members.contains(me) || !target.invites.remove(me)) {
            source.sendFailure(Component.translatable("siftec.company.no_invite"));
            return 0;
        }
        Company old = data.ofMember(me);
        if (old != null) {
            old.members.remove(me);
            // nothing can be picked up a second time by changing company
            target.collected.addAll(old.collected);
            if (old.members.isEmpty()) {
                // the last one out brings the base along: claims, machines, the cloud and the points
                target.points += old.points;
                old.cloud.forEach((item, count) -> target.cloud.merge(item, count, Integer::sum));
                data.fold(old.id, target.id);
                com.meakaandre.siftec.claim.Claims.reassign(server, old.id, target.id);
                com.meakaandre.siftec.place.Places.reassign(server, old.id, target.id);
            }
        }
        target.members.add(me);
        data.setDirty();
        SpeedCap.recompute(server);
        tell(server, target, Component.translatable("siftec.company.joined", player.getGameProfile().name(), target.name));
        return 1;
    }

    private static int leave(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CompanyData data = CompanyData.get(source.getServer());
        Company old = of(player);
        if (old.members.size() < 2) {
            source.sendFailure(Component.translatable("siftec.company.alone"));
            return 0;
        }
        old.members.remove(player.getUUID().toString());
        Company fresh = create(data, player);
        fresh.collected.addAll(old.collected);
        data.setDirty();
        SpeedCap.recompute(source.getServer());
        source.sendSuccess(() -> Component.translatable("siftec.company.left", fresh.name), false);
        return 1;
    }
}
