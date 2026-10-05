package com.meakaandre.siftec.client;

import com.meakaandre.siftec.registry.ModBlockEntities;
import com.zurrtum.create.client.content.kinetics.base.ShaftRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;

public class SiftecClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // the miner's input: a vertical shaft through the block, turning with the network
        BlockEntityRendererRegistry.register(ModBlockEntities.MINER.get(), ShaftRenderer::new);
    }
}
