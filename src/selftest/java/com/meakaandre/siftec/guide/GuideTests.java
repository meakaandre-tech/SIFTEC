package com.meakaandre.siftec.guide;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.registry.ModItems;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The guide's checks. {@code /siftec selftest guide} checks, on the server, that every building
 * and vehicle has its stand-in item and its Ponder lines, and every milestone and phase its text.
 * {@code /siftec worldtest guide check} has every player's client build every SIFTEC Ponder scene and look up every
 * line it shows (client/ponder/Guide.java logs the results as "SELFTEST guide-client ..."), and
 * {@code /siftec worldtest guide open <building>} and {@code close} open and close a building's scene, for the smoke
 * run's screenshots.
 */
public final class GuideTests {
    /** What the server tells a client to do: check every scene, open a building's scene, or close it. */
    public enum Action {
        CHECK, OPEN, CLOSE
    }

    /** A remote-control command: the action, sent by its ordinal, and its argument (the building to open). */
    public record Control(Action action, String arg) implements CustomPacketPayload {
        public static final Type<Control> TYPE = new Type<>(Siftec.id("guide_control"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Control> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.action.ordinal());
            buf.writeUtf(p.arg);
        }, buf -> new Control(Action.values()[buf.readVarInt()], buf.readUtf(64)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static int passed, failed;

    private GuideTests() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(Control.TYPE, Control.CODEC);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("guide").executes(GuideTests::run)))
                .then(Commands.literal("worldtest").then(Commands.literal("guide")
                    .then(Commands.literal("check").executes(context -> control(context, Action.CHECK, "")))
                    .then(Commands.literal("close").executes(context -> control(context, Action.CLOSE, "")))
                    .then(Commands.literal("open").then(Commands.argument("building", StringArgumentType.word())
                        .executes(context -> control(context, Action.OPEN, StringArgumentType.getString(context, "building")))))))));
    }

    private static int control(CommandContext<CommandSourceStack> context, Action action, String arg) {
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(player, Control.TYPE)) ServerPlayNetworking.send(player, new Control(action, arg));
        }
        return 1;
    }

    private static void report(CommandSourceStack source, String text) {
        String line = "SELFTEST guide " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        report(source, (ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        passed = 0;
        failed = 0;

        // every building and vehicle the Build Gun puts down has its stand-in item and its own lines
        List<String> noItem = new ArrayList<>(), noLines = new ArrayList<>();
        Set<String> guided = new HashSet<>();
        for (JsonElement e : Milestones.raw().getAsJsonArray("buildings")) {
            JsonObject b = e.getAsJsonObject();
            if (b.has("guide") && !b.getAsJsonArray("guide").isEmpty()) guided.add(b.get("id").getAsString());
        }
        int buildings = 0, vehicles = 0;
        for (BuildingDef def : Buildings.all()) {
            if (def.dev()) continue;
            buildings++;
            if (def.vehicle()) vehicles++;
            var item = BuiltInRegistries.ITEM.getOptional(Siftec.id("building_" + def.id()));
            if (item.isEmpty() || ModItems.BUILDINGS.get(def.id()) != item.get()) noItem.add(def.id());
            if (!guided.contains(def.id())) noLines.add(def.id());
        }
        expect(source, "every building and vehicle has its guide item (siftec:building_<id>)", noItem.isEmpty() && buildings > 0,
            buildings + " buildings, " + vehicles + " of them vehicles" + (noItem.isEmpty() ? "" : "; missing " + noItem));
        expect(source, "every building and vehicle has its own Ponder lines", noLines.isEmpty(), noLines.isEmpty() ? "" : "missing " + noLines);
        expect(source, "the dev buildings have no guide item", Buildings.all().stream().filter(BuildingDef::dev)
            .noneMatch(def -> BuiltInRegistries.ITEM.containsKey(Siftec.id("building_" + def.id()))), "");

        // every HUB milestone and Wormhole phase has its text
        Set<String> described = new HashSet<>();
        for (String list : new String[]{"milestones", "phases"}) {
            for (JsonElement e : Milestones.raw().getAsJsonArray(list)) {
                JsonObject m = e.getAsJsonObject();
                if (m.has("desc") && m.get("desc").getAsBoolean()) described.add(m.get("id").getAsString());
            }
        }
        List<String> noText = new ArrayList<>();
        int milestones = 0;
        for (int tier = 0; tier < Milestones.TIERS; tier++) {
            for (Milestone m : Milestones.tier(tier)) {
                milestones++;
                if (!described.contains(m.id())) noText.add(m.id());
            }
        }
        for (Milestone m : Milestones.phases()) {
            milestones++;
            if (!described.contains(m.id())) noText.add(m.id());
        }
        expect(source, "every milestone and Wormhole phase has its text", noText.isEmpty() && milestones > 0,
            milestones + " milestones and phases" + (noText.isEmpty() ? "" : "; missing " + noText));

        report(source, "done: " + passed + " passed, " + failed + " failed");
        return failed == 0 ? 1 : 0;
    }
}
