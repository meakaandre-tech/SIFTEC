package com.meakaandre.siftec;

import com.meakaandre.siftec.backpack.Backpack;
import com.meakaandre.siftec.claim.Claims;
import com.meakaandre.siftec.command.SiftecCommands;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.fluid.ModFluids;
import com.meakaandre.siftec.food.Food;
import com.meakaandre.siftec.hub.Locks;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.node.NodePlacer;
import com.meakaandre.siftec.registry.ModBlockEntities;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.registry.ModItems;
import com.meakaandre.siftec.registry.ModTab;
import com.meakaandre.siftec.net.StatePayload;
import com.meakaandre.siftec.tweak.SpeedCap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.resources.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** SIFTEC: Spatial Interlink and Factory Technology Engineering Corporation. */
public class Siftec implements ModInitializer {
    public static final String MOD_ID = "siftec";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        Milestones.load();
        ModBlocks.register();
        ModBlockEntities.register();
        ModItems.register();
        com.meakaandre.siftec.hub.HubMenu.register();
        ModFluids.register();
        ModTab.register();
        NodePlacer.register();
        com.meakaandre.siftec.node.NodeRegrowth.register();
        SiftecCommands.register();
        PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.STREAM_CODEC);
        ServerLifecycleEvents.SERVER_STARTED.register(SpeedCap::recompute);
        com.meakaandre.siftec.owner.Ownership.register();
        com.meakaandre.siftec.owner.Boosts.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> com.meakaandre.siftec.owner.RecipeLocks.forget());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> com.meakaandre.siftec.owner.RecipeLocks.forget());
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, manager, success) -> com.meakaandre.siftec.owner.RecipeLocks.forget());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> com.meakaandre.siftec.building.MachineTimes.forget());
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, manager, success) -> com.meakaandre.siftec.building.MachineTimes.forget());
        Backpack.register();
        // the pack's backpack is Traveler's Backpack; SIFTEC still loads without it (no backpack then)
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("travelersbackpack")) com.meakaandre.siftec.compat.TravelersBackpackCompat.register();
        com.meakaandre.siftec.equip.Equipment.register();
        Food.register();
        Locks.register();
        Companies.register();
        Claims.register();
        com.meakaandre.siftec.prefab.Prefabs.register();
        com.meakaandre.siftec.building.Buildings.register();
        com.meakaandre.siftec.building.BuildGun.register();
        com.meakaandre.siftec.power.PowerGrids.register();
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> com.meakaandre.siftec.building.BuildingPower.forget());
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, manager, success) -> com.meakaandre.siftec.building.BuildingPower.forget());
        com.meakaandre.siftec.geyser.Radiation.register();
        com.meakaandre.siftec.compat.ToxicShot.register();
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
