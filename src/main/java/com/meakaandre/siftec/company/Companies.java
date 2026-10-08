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
        // valuable changes (payments, the cloud, points) are written within a few seconds instead of at the next autosave
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (urgent && server.getTickCount() - lastUrgentSave >= URGENT_SAVE_TICKS) {
                urgent = false;
                lastUrgentSave = server.getTickCount();
                server.getDataStorage().scheduleSave();
            }
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            com.meakaandre.siftec.config.SiftecConfig.load();
            com.meakaandre.siftec.save.JsonSavedData.serverStarting(server);
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            urgent = false;
            lastUrgentSave = 0;
            com.meakaandre.siftec.save.JsonSavedData.serverStopped();
            // per-player choices held in memory belong to this server run only
            com.meakaandre.siftec.item.NodeScannerItem.forget();
            com.meakaandre.siftec.item.ObjectScannerItem.forget();
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
                .then(Commands.literal("kick").then(Commands.argument("player", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        ServerPlayer me = context.getSource().getPlayer();
                        if (me != null) {
                            for (String member : of(me).members) builder.suggest(nameOf(context.getSource().getServer(), member));
                        }
                        return builder.buildFuture();
                    })
                    .executes(context -> kick(context.getSource(), StringArgumentType.getString(context, "player")))))
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

    /** Ticks between two quick saves of valuable changes. */
    private static final int URGENT_SAVE_TICKS = 100;
    private static boolean urgent;
    private static int lastUrgentSave;

    /**
     * The player's company; a one-person company is made the first time it is asked for. A machine's fake player
     * (a Deployer) gets its owner's company, or an empty stand-in that is never saved when the owner has none.
     */
    public static Company of(ServerPlayer player) {
        CompanyData data = CompanyData.get(player.level().getServer());
        Company company = data.ofMember(player.getUUID().toString());
        if (company == null) company = isFake(player) ? nobody() : create(data, player.getUUID(), player.getGameProfile().name());
        return company;
    }

    /** The player's company if they have one; never makes one. */
    public static @org.jspecify.annotations.Nullable Company find(ServerPlayer player) {
        return CompanyData.get(player.level().getServer()).ofMember(player.getUUID().toString());
    }

    /** True for players that machines use (Create's Deployer, or any Fabric fake player). */
    public static boolean isFake(Player player) {
        return player instanceof net.fabricmc.fabric.api.entity.FakePlayer || com.zurrtum.create.api.entity.FakePlayerHandler.has(player);
    }

    /** A company of nobody: its id is empty, so whatever it is stamped on counts as unowned. */
    private static Company nobody() {
        Company company = new Company();
        company.id = "";
        company.name = "?";
        return company;
    }

    /** The name the server knows for a player, online or not. */
    public static String nameOf(MinecraftServer server, String member) {
        try {
            UUID uuid = UUID.fromString(member);
            ServerPlayer online = server.getPlayerList().getPlayer(uuid);
            if (online != null) return online.getGameProfile().name();
            return server.services().nameToIdCache().get(uuid).map(net.minecraft.server.players.NameAndId::name).orElse(member.substring(0, Math.min(8, member.length())));
        } catch (IllegalArgumentException e) {
            return member;
        }
    }

    /** Null on the client, where company state is not known. */
    public static Company of(Player player) {
        return player instanceof ServerPlayer server ? of(server) : null;
    }

    private static Company create(CompanyData data, UUID player, String name) {
        Company company = new Company();
        company.name = name + "'s Company";
        company.members.add(player.toString());
        company.owner = player.toString();
        data.companies().put(company.id, company);
        data.setDirty();
        return company;
    }

    /** Marks the companies changed and writes them to disk within a few seconds, not at the next autosave. */
    public static void save(MinecraftServer server) {
        CompanyData.get(server).setDirty();
        urgent = true;
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
            names.append(online != null ? online.getGameProfile().name() : nameOf(server, member));
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
        if (target.members.contains(me) || !target.invites.contains(me)) {
            source.sendFailure(Component.translatable("siftec.company.no_invite"));
            return 0;
        }
        if (target.coolingDown(me)) {
            source.sendFailure(Component.translatableWithFallback("siftec.company.cooldown",
                "You left %s less than %s hours ago and cannot join it again yet", target.name, com.meakaandre.siftec.config.SiftecConfig.memberCooldownHours));
            return 0;
        }
        target.invites.remove(me);
        Company old = data.ofMember(me);
        // parts already delivered keep their share of the new costs
        target.syncPaid();
        if (old != null) {
            old.syncPaid();
            old.members.remove(me);
            // nothing can be picked up a second time by changing company
            target.collected.addAll(old.collected);
            if (old.members.isEmpty()) {
                // the last one out brings the base along: claims, machines, the cloud, the points and parts paid
                target.points += old.points;
                old.cloud.forEach((item, count) -> target.cloud.merge(item, count, Integer::sum));
                mergePaid(old, target);
                data.fold(old.id, target.id);
                com.meakaandre.siftec.claim.Claims.reassign(server, old.id, target.id);
                com.meakaandre.siftec.place.Places.reassign(server, old.id, target.id);
            } else {
                old.leftAt.put(me, System.currentTimeMillis());
            }
        }
        target.members.add(me);
        target.leftAt.remove(me);
        save(server);
        SpeedCap.recompute(server);
        com.meakaandre.siftec.claim.Claims.refreshLoading(server);
        tell(server, target, Component.translatable("siftec.company.joined", player.getGameProfile().name(), target.name));
        return 1;
    }

    /** Parts a folding company had delivered move to the company that takes it over, scaled to its costs. */
    private static void mergePaid(Company from, Company to) {
        float ratio = to.costMultiplier() / Math.max(1f, from.costMultiplier());
        from.paid.forEach((milestone, parts) -> {
            if (to.has(milestone)) return;
            java.util.Map<String, Integer> into = to.paid.computeIfAbsent(milestone, k -> new java.util.HashMap<>());
            parts.forEach((item, count) -> into.merge(item, Math.round(count * ratio), Integer::sum));
        });
    }

    private static int leave(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CompanyData data = CompanyData.get(source.getServer());
        Company old = of(player);
        if (old.members.size() < 2) {
            source.sendFailure(Component.translatable("siftec.company.alone"));
            return 0;
        }
        Company fresh = remove(source.getServer(), data, old, player.getUUID(), player.getGameProfile().name());
        source.sendSuccess(() -> Component.translatable("siftec.company.left", fresh.name), false);
        return 1;
    }

    /** Takes a member out into a company of their own. They still count toward the old one's costs for a while. */
    private static Company remove(MinecraftServer server, CompanyData data, Company old, UUID player, String name) {
        String id = player.toString();
        old.syncPaid();
        old.members.remove(id);
        old.leftAt.put(id, System.currentTimeMillis());
        if (id.equals(old.owner)) old.owner = old.owner();
        Company fresh = create(data, player, name);
        fresh.collected.addAll(old.collected);
        save(server);
        SpeedCap.recompute(server);
        com.meakaandre.siftec.claim.Claims.refreshLoading(server);
        return fresh;
    }

    /** The founder can put a member (online or not) out of the company. */
    private static int kick(CommandSourceStack source, String name) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        MinecraftServer server = source.getServer();
        CompanyData data = CompanyData.get(server);
        Company company = of(player);
        if (!company.owner().equals(player.getUUID().toString())) {
            source.sendFailure(Component.translatableWithFallback("siftec.company.not_owner", "Only the founder of %s can kick members", company.name));
            return 0;
        }
        String target = null;
        for (String member : company.members) if (nameOf(server, member).equalsIgnoreCase(name) || member.equalsIgnoreCase(name)) target = member;
        if (target == null || target.equals(player.getUUID().toString())) {
            source.sendFailure(Component.translatableWithFallback("siftec.company.not_member", "%s is not another member of %s", name, company.name));
            return 0;
        }
        String targetName = nameOf(server, target);
        Company fresh = remove(server, data, company, UUID.fromString(target), targetName);
        tell(server, company, Component.translatableWithFallback("siftec.company.kicked", "%s was removed from %s", targetName, company.name));
        ServerPlayer online = server.getPlayerList().getPlayer(UUID.fromString(target));
        if (online != null) online.sendSystemMessage(Component.translatableWithFallback("siftec.company.you_were_kicked", "You were removed from %s and now run %s", company.name, fresh.name));
        return 1;
    }

    /** For the self-test: kicks without a command source. Returns false if the founder may not. */
    public static boolean kick(MinecraftServer server, Company company, String founder, String member) {
        if (!company.owner().equals(founder) || !company.members.contains(member) || member.equals(founder)) return false;
        remove(server, CompanyData.get(server), company, UUID.fromString(member), nameOf(server, member));
        return true;
    }
}
