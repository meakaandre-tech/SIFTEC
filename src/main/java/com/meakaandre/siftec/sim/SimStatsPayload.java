package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.Siftec;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/**
 * Server to client, once a second: the factory simulation's figures for the debug overlay (F3): how many buildings it
 * knows and how many it runs, in how many groups, the vehicles it drives, and the time it takes a tick on the server
 * thread and a step on its worker.
 */
public record SimStatsPayload(int nodes, int simulated, int groups, int vehicles, float mainMs, float workerMs, float workerStepMs)
    implements CustomPacketPayload {
    public static final Type<SimStatsPayload> TYPE = new Type<>(Siftec.id("sim_stats"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SimStatsPayload> CODEC = StreamCodec.of((buf, p) -> {
        buf.writeVarInt(p.nodes);
        buf.writeVarInt(p.simulated);
        buf.writeVarInt(p.groups);
        buf.writeVarInt(p.vehicles);
        buf.writeFloat(p.mainMs);
        buf.writeFloat(p.workerMs);
        buf.writeFloat(p.workerStepMs);
    }, buf -> new SimStatsPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat()));

    static void register() {
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
    }

    static void send(MinecraftServer server, Factories.Stats s) {
        SimStatsPayload p = new SimStatsPayload(s.nodes(), s.simulated(), s.groups(), s.vehicles(), (float) s.mainMsPerTick(),
            (float) s.workerMsPerTick(), (float) s.workerMsPerStep());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(player, TYPE)) ServerPlayNetworking.send(player, p);
        }
    }

    /** The line the debug overlay shows. */
    public String line() {
        return String.format(Locale.ROOT, "SIFTEC factories: %d/%d simulated, %d groups, %d vehicles | %.3f ms/tick (server %.3f, worker %.3f; %.2f a step)",
            simulated, nodes, groups, vehicles, mainMs + workerMs, mainMs, workerMs, workerStepMs);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
