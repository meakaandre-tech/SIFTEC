package com.meakaandre.siftec.registry;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.block.ExtractorBlockEntity;
import com.meakaandre.siftec.block.MinerBlockEntity;
import com.meakaandre.siftec.depot.DepotBlockEntity;
import com.meakaandre.siftec.geyser.GeyserEngineBlockEntity;
import com.meakaandre.siftec.hub.HubBlockEntity;
import com.meakaandre.siftec.machine.ProcessorBlockEntity;
import com.meakaandre.siftec.sink.SinkBlockEntity;
import com.meakaandre.siftec.power.PoleBlockEntity;
import com.meakaandre.siftec.power.StorageBlockEntity;
import com.meakaandre.siftec.block.PortableMinerBlockEntity;
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

    public static final Entry<PortableMinerBlockEntity> PORTABLE_MINER = register("portable_miner", PortableMinerBlockEntity::new, ModBlocks.PORTABLE_MINER.get());
    public static final Entry<MinerBlockEntity> MINER = register("miner", MinerBlockEntity::new, ModBlocks.MINER_MK1.get(), ModBlocks.MINER_MK2.get(), ModBlocks.MINER_MK3.get());
    public static final Entry<com.zurrtum.create.content.kinetics.base.KineticBlockEntity> MINER_TOP = register("miner_top", com.zurrtum.create.content.kinetics.base.KineticBlockEntity::new, ModBlocks.MINER_TOP.get());
    public static final Entry<ExtractorBlockEntity> EXTRACTOR = register("resource_well_extractor", ExtractorBlockEntity::new, ModBlocks.RESOURCE_WELL_EXTRACTOR.get());

    public static final Entry<HubBlockEntity> HUB = register("hub", HubBlockEntity::new, ModBlocks.HUB.get(), ModBlocks.WORMHOLE_GATEWAY.get());

    public static final Entry<DepotBlockEntity> DEPOT = register("dimensional_depot", DepotBlockEntity::new, ModBlocks.DIMENSIONAL_DEPOT.get());
    public static final Entry<PoleBlockEntity> POLE = register("power_pole", PoleBlockEntity::new, ModBlocks.POWER_POLE.get(), ModBlocks.POWER_TOWER.get());
    public static final Entry<com.meakaandre.siftec.governor.GovernorBlockEntity> GOVERNOR = register("speed_governor", com.meakaandre.siftec.governor.GovernorBlockEntity::new, ModBlocks.SPEED_GOVERNOR.get());
    public static final Entry<com.meakaandre.siftec.engine.EngineBlockEntity> ENGINE = register("engine", com.meakaandre.siftec.engine.EngineBlockEntity::new, ModBlocks.FURNACE_ENGINE.get(), ModBlocks.HUB_ENGINE.get());
    public static final Entry<com.meakaandre.siftec.drone.DronePortBlockEntity> DRONE_PORT = register("drone_port", com.meakaandre.siftec.drone.DronePortBlockEntity::new, ModBlocks.DRONE_PORT.get());
    public static final Entry<com.meakaandre.siftec.blueprint.DesignerBlockEntity> DESIGNER = register("blueprint_designer", com.meakaandre.siftec.blueprint.DesignerBlockEntity::new, ModBlocks.BLUEPRINT_DESIGNER.get(), ModBlocks.BLUEPRINT_DESIGNER_MK3.get());
    public static final Entry<StorageBlockEntity> POWER_STORAGE = register("power_storage", StorageBlockEntity::new, ModBlocks.POWER_STORAGE.get());

    public static final Entry<GeyserEngineBlockEntity> GEYSER_ENGINE = register("geyser_engine", GeyserEngineBlockEntity::new, ModBlocks.GEYSER_ENGINE.get());

    public static final Entry<ProcessorBlockEntity> PROCESSOR = register("processor", ProcessorBlockEntity::new, ModBlocks.CONVERTER.get(), ModBlocks.PARTICLE_ACCELERATOR.get());
    public static final Entry<com.meakaandre.siftec.workshop.WorkshopBlockEntity> WORKSHOP = register("equipment_workshop", com.meakaandre.siftec.workshop.WorkshopBlockEntity::new, ModBlocks.EQUIPMENT_WORKSHOP.get());
    public static final Entry<com.meakaandre.siftec.mam.MamBlockEntity> MAM = register("mam", com.meakaandre.siftec.mam.MamBlockEntity::new, ModBlocks.MAM.get());
    public static final Entry<SinkBlockEntity> SINK = register("awesome_sink", SinkBlockEntity::new, ModBlocks.AWESOME_SINK.get());

    private static <T extends BlockEntity> Entry<T> register(String name, Factory<T> factory, Block... blocks) {
        Entry<T> entry = new Entry<>();
        BlockEntityType<T> type = new BlockEntityType<>((pos, state) -> factory.create(entry.get(), pos, state), Set.of(blocks));
        entry.type = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Siftec.id(name), type);
        return entry;
    }

    public static void register() {
        // belts, funnels, chutes and hoppers can take a powered miner's output
        ItemStorage.SIDED.registerForBlockEntity((miner, side) -> ContainerStorage.of(miner.output, side), MINER.get());
        // parts pushed into a HUB or Gateway go toward the company's active milestone
        ItemStorage.SIDED.registerForBlockEntity((hub, side) -> hub.intake, HUB.get());
        ItemStorage.SIDED.registerForBlockEntity((engine, side) -> ContainerStorage.of(engine.fuel, side), ENGINE.get());
        ItemStorage.SIDED.registerForBlockEntity((port, side) -> ContainerStorage.of(port.items, side), DRONE_PORT.get());
        ItemStorage.SIDED.registerForBlockEntity((sink, side) -> sink.intake, SINK.get());
        ItemStorage.SIDED.registerForBlockEntity((machine, side) -> ContainerStorage.of(machine.items, side), PROCESSOR.get());
        // parts for the Workshop's picked build go in; what it builds comes out
        ItemStorage.SIDED.registerForBlockEntity((workshop, side) -> workshop.storage(), WORKSHOP.get());
        // research parts pushed into a MAM go to the node its company picked for delivery
        ItemStorage.SIDED.registerForBlockEntity((mam, side) -> mam.intake, MAM.get());
    }
}
