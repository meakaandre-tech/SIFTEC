package com.meakaandre.siftec.client;

import com.meakaandre.siftec.registry.ModBlockEntities;
import com.zurrtum.create.client.content.kinetics.base.ShaftRenderer;
import com.zurrtum.create.client.content.kinetics.base.SingleKineticRenderState;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import com.meakaandre.siftec.net.ClientState;
import com.meakaandre.siftec.net.StatePayload;
import com.meakaandre.siftec.tweak.SpeedCap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;

public class SiftecClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // the miner's input: a vertical shaft through the block, turning with the network
        BlockEntityRendererProvider<KineticBlockEntity, SingleKineticRenderState> shaft = ShaftRenderer::new;
        BlockEntityRendererRegistry.register(ModBlockEntities.MINER.get(), shaft);
        ClientPlayNetworking.registerGlobalReceiver(StatePayload.TYPE, (payload, context) -> ClientState.accept(payload));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientState.done = null;
            SpeedCap.value = 256;
        });
    }
}
