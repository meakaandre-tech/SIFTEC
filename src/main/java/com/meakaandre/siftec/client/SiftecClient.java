package com.meakaandre.siftec.client;

import com.meakaandre.siftec.registry.ModBlockEntities;
import com.zurrtum.create.client.content.kinetics.base.ShaftRenderer;
import com.zurrtum.create.client.content.kinetics.base.SingleKineticRenderState;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import com.meakaandre.siftec.fluid.FluidEntry;
import com.meakaandre.siftec.fluid.ModFluids;
import com.meakaandre.siftec.net.ClientState;
import com.zurrtum.create.client.AllFluidConfigs;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.resources.Identifier;
import com.meakaandre.siftec.net.StatePayload;
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
        BlockEntityRendererRegistry.register(ModBlockEntities.EXTRACTOR.get(), shaft);
        BlockEntityRendererRegistry.register(ModBlockEntities.POLE.get(), shaft);
        BlockEntityRendererRegistry.register(ModBlockEntities.PROCESSOR.get(), shaft);
        BlockEntityRendererRegistry.register(ModBlockEntities.GEYSER_ENGINE.get(), shaft);
        BlockEntityRendererRegistry.register(ModBlockEntities.POWER_STORAGE.get(), shaft);
        BlockEntityRendererRegistry.register(ModBlockEntities.ENGINE.get(), shaft);
        // the Speed Governor's two shaft halves turn at different speeds, like a Gearshift's
        BlockEntityRendererProvider<com.zurrtum.create.content.kinetics.transmission.SplitShaftBlockEntity,
            com.zurrtum.create.client.content.kinetics.transmission.SplitShaftRenderer.SplitShaftRenderState> split =
            com.zurrtum.create.client.content.kinetics.transmission.SplitShaftRenderer::new;
        BlockEntityRendererRegistry.register(ModBlockEntities.GOVERNOR.get(), split);
        // custom fluids borrow water's textures and are told apart by colour
        for (FluidEntry fluid : ModFluids.ALL.values()) {
            AllFluidConfigs.MODEL.put(fluid.still, new FluidModel.Unbaked(
                new Material(Identifier.withDefaultNamespace("block/water_still")), new Material(Identifier.withDefaultNamespace("block/water_flow")), null, null));
            int color = 0xFF000000 | fluid.color;
            AllFluidConfigs.tint(fluid.still, (f, components) -> color);
        }
        // every locked item says, in red, what unlocks it: in the inventory and in recipe viewers alike
        net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            net.minecraft.network.chat.Component why = ClientLocks.item(stack);
            if (why != null) lines.add(why.copy().withStyle(net.minecraft.ChatFormatting.RED));
        });
        ClientPlayNetworking.registerGlobalReceiver(StatePayload.TYPE, (payload, context) -> ClientState.accept(payload));
        ClientPlayNetworking.registerGlobalReceiver(com.meakaandre.siftec.equip.JetFuelPayload.TYPE, (payload, context) -> ClientState.jetFuel = payload.ok());
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(JetpackClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientState.done = null;
            ClientState.backpackSlots = 0;
            ClientState.jetFuel = true;
        });
    }
}
