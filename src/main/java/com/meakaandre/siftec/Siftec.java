package com.meakaandre.siftec;

import com.meakaandre.siftec.building.BuildGun;
import com.meakaandre.siftec.building.BuildingPower;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.ExtractorLogic;
import com.meakaandre.siftec.building.MachineTimes;
import com.meakaandre.siftec.building.TruckStations;
import com.meakaandre.siftec.claim.Claims;
import com.meakaandre.siftec.collect.AncientCities;
import com.meakaandre.siftec.command.SiftecCommands;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.compat.ToxicShot;
import com.meakaandre.siftec.compat.TravelersBackpackCompat;
import com.meakaandre.siftec.cosmetic.Emotes;
import com.meakaandre.siftec.death.DeathCrates;
import com.meakaandre.siftec.depot.OpenCloudPayload;
import com.meakaandre.siftec.drone.DroneEntity;
import com.meakaandre.siftec.equip.Equipment;
import com.meakaandre.siftec.fluid.ModFluids;
import com.meakaandre.siftec.food.Food;
import com.meakaandre.siftec.geyser.GeyserSearch;
import com.meakaandre.siftec.geyser.Radiation;
import com.meakaandre.siftec.hub.HubMenu;
import com.meakaandre.siftec.hub.Locks;
import com.meakaandre.siftec.hub.MapLock;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.map.MapServer;
import com.meakaandre.siftec.net.CompanyState;
import com.meakaandre.siftec.net.StatePayload;
import com.meakaandre.siftec.node.NodePlacer;
import com.meakaandre.siftec.node.NodeRegrowth;
import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.owner.RecipeLocks;
import com.meakaandre.siftec.power.PowerGrids;
import com.meakaandre.siftec.prefab.Prefabs;
import com.meakaandre.siftec.registry.ModBlockEntities;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.registry.ModItems;
import com.meakaandre.siftec.registry.ModTab;
import com.meakaandre.siftec.sim.Factories;
import com.meakaandre.siftec.vehicle.Vehicles;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
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
        HubMenu.register();
        ModFluids.register();
        ModTab.register();
        NodePlacer.register();
        AncientCities.register();
        MapLock.register();
        NodeRegrowth.register();
        SiftecCommands.register();
        PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.STREAM_CODEC);
        ServerLifecycleEvents.SERVER_STARTED.register(CompanyState::recompute);
        Ownership.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> RecipeLocks.forget());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> RecipeLocks.forget());
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, manager, success) -> RecipeLocks.forget());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> MachineTimes.forget());
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, manager, success) -> MachineTimes.forget());
        // the inventory is the Traveler's Backpack each player wears (SIFTEC still loads without it: vanilla inventory
        // then); a dead player's things go into a Death Crate either way
        DeathCrates.register();
        if (FabricLoader.getInstance().isModLoaded("travelersbackpack")) TravelersBackpackCompat.register();
        Equipment.register();
        OpenCloudPayload.register();
        Food.register();
        Locks.register();
        Companies.register();
        MapServer.register();
        Claims.register();
        Prefabs.register();
        Buildings.register();
        BuildGun.register();
        ExtractorLogic.register();
        TruckStations.register();
        Vehicles.register();
        SelfTestHooks.run(SelfTestHooks.Stage.CONTENT);
        PowerGrids.register();
        Factories.register();
        SelfTestHooks.run(SelfTestHooks.Stage.SIMULATION);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> BuildingPower.forget());
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, manager, success) -> BuildingPower.forget());
        Radiation.register();
        GeyserSearch.register();
        DroneEntity.register();
        SelfTestHooks.run(SelfTestHooks.Stage.LATE);
        Emotes.register();
        ToxicShot.register();
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
