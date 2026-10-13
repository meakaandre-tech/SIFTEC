package com.meakaandre.siftec.registry;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.block.ExtractorBlockEntity;
import com.meakaandre.siftec.depot.DepotBlockEntity;
import com.meakaandre.siftec.hub.HubBlockEntity;
import com.meakaandre.siftec.machine.ProcessorBlockEntity;
import com.meakaandre.siftec.sink.SinkBlockEntity;
import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.function.Supplier;

public class ModBlockEntities {
    @FunctionalInterface
    private interface Factory<T extends BlockEntity> {
        T create(BlockEntityType<?> type, BlockPos pos, BlockState state);
    }

    /** Filled in right after registration; the type has to exist before its factory can hand it out. */
    public static final class Entry<T extends BlockEntity> implements Supplier<BlockEntityType<T>> {
        private BlockEntityType<T> type;

        @Override
        public BlockEntityType<T> get() {
            return type;
        }
    }

    public static final Entry<ExtractorBlockEntity> EXTRACTOR = register("resource_well_extractor", ExtractorBlockEntity::new, ModBlocks.RESOURCE_WELL_EXTRACTOR.get());

    public static final Entry<HubBlockEntity> HUB = register("hub", HubBlockEntity::new, ModBlocks.HUB.get(), ModBlocks.WORMHOLE_GATEWAY.get());

    public static final Entry<DepotBlockEntity> DEPOT = register("dimensional_depot", DepotBlockEntity::new, ModBlocks.DIMENSIONAL_DEPOT.get());
    public static final Entry<com.meakaandre.siftec.governor.GovernorBlockEntity> GOVERNOR = register("speed_governor", com.meakaandre.siftec.governor.GovernorBlockEntity::new, ModBlocks.SPEED_GOVERNOR.get());
    /** The HUB's Biomass Burners, generators on the grid of the connector on top. */
    public static final Entry<com.meakaandre.siftec.prefab.HubBurnerBlockEntity> HUB_BURNER = register("hub_burner", com.meakaandre.siftec.prefab.HubBurnerBlockEntity::new, ModBlocks.HUB_BURNER.get());
    /** The HUB's Personal Storage Box. */
    public static final Entry<com.meakaandre.siftec.prefab.StorageBoxBlockEntity> STORAGE_BOX = register("personal_storage", com.meakaandre.siftec.prefab.StorageBoxBlockEntity::new, ModBlocks.PERSONAL_STORAGE.get());
    public static final Entry<com.meakaandre.siftec.drone.DronePortBlockEntity> DRONE_PORT = register("drone_port", com.meakaandre.siftec.drone.DronePortBlockEntity::new, ModBlocks.DRONE_PORT.get());
    public static final Entry<com.meakaandre.siftec.blueprint.DesignerBlockEntity> DESIGNER = register("blueprint_designer", com.meakaandre.siftec.blueprint.DesignerBlockEntity::new, ModBlocks.BLUEPRINT_DESIGNER.get(), ModBlocks.BLUEPRINT_DESIGNER_MK3.get());


    public static final Entry<ProcessorBlockEntity> PROCESSOR = register("processor", ProcessorBlockEntity::new, ModBlocks.CONVERTER.get(), ModBlocks.PARTICLE_ACCELERATOR.get());
    /** Only holds what the Workshop's removed automatic mode had in it, until it is given back. */
    public static final Entry<com.meakaandre.siftec.workshop.WorkshopBlockEntity> WORKSHOP = register("equipment_workshop", com.meakaandre.siftec.workshop.WorkshopBlockEntity::new, ModBlocks.EQUIPMENT_WORKSHOP.get());
    public static final Entry<SinkBlockEntity> SINK = register("awesome_sink", SinkBlockEntity::new, ModBlocks.AWESOME_SINK.get());

    public static final Entry<com.meakaandre.siftec.building.ControllerBlockEntity> BUILDING_CONTROLLER = register("building_controller", com.meakaandre.siftec.building.ControllerBlockEntity::new, ModBlocks.BUILDING_CONTROLLER.get());
    public static final Entry<com.meakaandre.siftec.building.PortBlockEntity> BUILDING_PORT = register("building_port", com.meakaandre.siftec.building.PortBlockEntity::new, ModBlocks.BUILDING_PORT.get());
    public static final Entry<com.meakaandre.siftec.building.TurbineBlockEntity> TURBINE = register("turbine", com.meakaandre.siftec.building.TurbineBlockEntity::new, ModBlocks.TURBINE.get());

    private static <T extends BlockEntity> Entry<T> register(String name, Factory<T> factory, Block... blocks) {
        Entry<T> entry = new Entry<>();
        BlockEntityType<T> type = new BlockEntityType<>((pos, state) -> factory.create(entry.get(), pos, state), Set.of(blocks));
        entry.type = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Siftec.id(name), type);
        return entry;
    }

    public static void register() {
        // belts, funnels, chutes and hoppers can take a powered miner's output
        // parts pushed into a HUB or Gateway go toward the company's active milestone
        ItemStorage.SIDED.registerForBlockEntity((hub, side) -> hub.intake, HUB.get());
        ItemStorage.SIDED.registerForBlockEntity((burner, side) -> ContainerStorage.of(burner.fuel, side), HUB_BURNER.get());
        ItemStorage.SIDED.registerForBlockEntity((box, side) -> ContainerStorage.of(box.items, side), STORAGE_BOX.get());
        ItemStorage.SIDED.registerForBlockEntity((port, side) -> ContainerStorage.of(port.items, side), DRONE_PORT.get());
        ItemStorage.SIDED.registerForBlockEntity((sink, side) -> sink.intake, SINK.get());
        ItemStorage.SIDED.registerForBlockEntity((machine, side) -> ContainerStorage.of(machine.items, side), PROCESSOR.get());
        // a building's ports pass items and fluids to and from the machines inside
        ItemStorage.SIDED.registerForBlockEntity((port, side) -> port.items(side), BUILDING_PORT.get());
        // what a building's ports reach when they point at its controller (a miner's ore, the Smelter's ore and ingots)
        ItemStorage.SIDED.registerForBlockEntity((controller, side) -> controller.storage(), BUILDING_CONTROLLER.get());
        net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage.SIDED.registerForBlockEntity((port, side) -> port.fluids(side), BUILDING_PORT.get());
    }
}
