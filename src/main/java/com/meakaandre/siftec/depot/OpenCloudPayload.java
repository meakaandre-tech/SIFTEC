package com.meakaandre.siftec.depot;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.company.Companies;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the Cloud button in the inventory screen was pressed; opens the company's cloud inventory. */
public record OpenCloudPayload() implements CustomPacketPayload {
    public static final Type<OpenCloudPayload> TYPE = new Type<>(Siftec.id("open_cloud"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenCloudPayload> CODEC = StreamCodec.unit(new OpenCloudPayload());

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) ->
            context.server().execute(() -> DepotMenu.open(context.player(), Companies.of(context.player()))));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
