package com.meakaandre.siftec;

import com.meakaandre.siftec.building.BuildGun;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * What the smoke run's client script waits for, in place of fixed sleeps, so a slow machine only makes it slower:
 * <ul>
 * <li>{@code /siftec worldtest ready <n>}: asks every client to say, in its own log, "SELFTEST client ready n." once
 * the world around its player has arrived and is drawn ({@code client.ReadyClientTests});</li>
 * <li>{@code /siftec worldtest gunwait <n>}: logs "SELFTEST smoke gun uses n." once the Build Gun has been used n times
 * since {@code /siftec worldtest buildgun} (or "... not reached" after {@link #GUN_WAIT} ticks).</li>
 * </ul>
 * The workflow's {@code wait} lines watch the logs for those lines.
 */
public final class SmokeWaits {
    /** Ticks a gunwait waits at most. */
    private static final int GUN_WAIT = 600;

    private static final TestQueue LATER = new TestQueue();
    private static final TestReport REPORT = new TestReport("SELFTEST smoke ");

    /** Server to client: tell the log once the world around the player is loaded and drawn, with this token. */
    public record Ready(int token) implements CustomPacketPayload {
        public static final Type<Ready> TYPE = new Type<>(Siftec.id("selftest_ready"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ready> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> buf.writeVarInt(p.token), buf -> new Ready(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private SmokeWaits() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(Ready.TYPE, Ready.STREAM_CODEC);
        LATER.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("worldtest")
                    .then(Commands.literal("ready").then(Commands.argument("token", IntegerArgumentType.integer(0)).executes(SmokeWaits::ready)))
                    .then(Commands.literal("gunwait").then(Commands.argument("uses", IntegerArgumentType.integer(1)).executes(SmokeWaits::gunWait))))));
    }

    private static int ready(CommandContext<CommandSourceStack> context) {
        int token = IntegerArgumentType.getInteger(context, "token");
        int sent = 0;
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, new Ready(token));
            sent++;
        }
        REPORT.report(context.getSource(), "ready " + token + " asked of " + sent + " players");
        return 1;
    }

    private static int gunWait(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int uses = IntegerArgumentType.getInteger(context, "uses");
        LATER.when(source.getServer().overworld().getGameTime(), () -> BuildGun.USES.size() >= uses, GUN_WAIT,
            reached -> REPORT.report(source, "gun uses " + uses + (reached ? "." : " not reached (" + BuildGun.USES.size() + ").")));
        return 1;
    }
}
