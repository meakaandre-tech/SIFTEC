package com.meakaandre.siftec.registry;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.block.ExtractorBlockEntity;
import com.meakaandre.siftec.blueprint.DesignerBlockEntity;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.PortBlockEntity;
import com.meakaandre.siftec.building.TurbineBlockEntity;
import com.meakaandre.siftec.cosmetic.ShopSignBlockEntity;
import com.meakaandre.siftec.death.DeathCrateBlockEntity;
import com.meakaandre.siftec.depot.DepotBlockEntity;
import com.meakaandre.siftec.drone.DronePortBlockEntity;
import com.meakaandre.siftec.governor.GovernorBlockEntity;
import com.meakaandre.siftec.hub.HubBlockEntity;
import com.meakaandre.siftec.mam.MamBlockEntity;
import com.meakaandre.siftec.prefab.HubBurnerBlockEntity;
import com.meakaandre.siftec.prefab.StorageBoxBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityType;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
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
    public static final Entry<GovernorBlockEntity> GOVERNOR = register("speed_governor", GovernorBlockEntity::new, ModBlocks.SPEED_GOVERNOR.get());
    /** The HUB's Biomass Burners, generators on the grid of the connector on top. */
    public static final Entry<HubBurnerBlockEntity> HUB_BURNER = register("hub_burner", HubBurnerBlockEntity::new, ModBlocks.HUB_BURNER.get());
    /** The HUB's Personal Storage Box. */
    public static final Entry<StorageBoxBlockEntity> STORAGE_BOX = register("personal_storage", StorageBoxBlockEntity::new, ModBlocks.PERSONAL_STORAGE.get());
    /** The MAM: which company it belongs to. */
    public static final Entry<MamBlockEntity> MAM = register("mam", MamBlockEntity::new, ModBlocks.MAM.get());
    public static final Entry<DronePortBlockEntity> DRONE_PORT = register("drone_port", DronePortBlockEntity::new, ModBlocks.DRONE_PORT.get());
    public static final Entry<DesignerBlockEntity> DESIGNER = register("blueprint_designer", DesignerBlockEntity::new, ModBlocks.BLUEPRINT_DESIGNER.get(), ModBlocks.BLUEPRINT_DESIGNER_MK2.get(), ModBlocks.BLUEPRINT_DESIGNER_MK3.get());


    public static final Entry<ControllerBlockEntity> BUILDING_CONTROLLER = register("building_controller", ControllerBlockEntity::new, ModBlocks.BUILDING_CONTROLLER.get());
    public static final Entry<PortBlockEntity> BUILDING_PORT = register("building_port", PortBlockEntity::new, ModBlocks.BUILDING_PORT.get());
    public static final Entry<DeathCrateBlockEntity> DEATH_CRATE = register("death_crate", DeathCrateBlockEntity::new, ModBlocks.DEATH_CRATE.get());
    public static final Entry<TurbineBlockEntity> TURBINE = register("turbine", TurbineBlockEntity::new, ModBlocks.TURBINE.get());

    /** The AWESOME Shop's signs. */
    public static final Entry<ShopSignBlockEntity> SHOP_SIGN = register("shop_sign", ShopSignBlockEntity::new,
        ModBlocks.LABEL_SIGN.get(), ModBlocks.DISPLAY_SIGN.get(), ModBlocks.BILLBOARD_SMALL_SIGN.get(), ModBlocks.BILLBOARD_LARGE_SIGN.get());

    private static <T extends BlockEntity> Entry<T> register(String name, Factory<T> factory, Block... blocks) {
        Entry<T> entry = new Entry<>();
        BlockEntityType<T> type = new BlockEntityType<>((pos, state) -> factory.create(entry.get(), pos, state), Set.of(blocks));
        entry.type = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Siftec.id(name), type);
        return entry;
    }

    public static void register() {
        // the Boom Box is a jukebox
        ((FabricBlockEntityType) (Object) BlockEntityTypes.JUKEBOX).addValidBlock(ModBlocks.BOOM_BOX.get());
        // belts, funnels, chutes and hoppers can take a powered miner's output
        // parts pushed into a HUB or Gateway go toward the company's active milestone
        ItemStorage.SIDED.registerForBlockEntity((hub, side) -> hub.intake, HUB.get());
        ItemStorage.SIDED.registerForBlockEntity((burner, side) -> ContainerStorage.of(burner.fuel, side), HUB_BURNER.get());
        ItemStorage.SIDED.registerForBlockEntity((box, side) -> ContainerStorage.of(box.items, side), STORAGE_BOX.get());
        ItemStorage.SIDED.registerForBlockEntity((port, side) -> ContainerStorage.of(port.items, side), DRONE_PORT.get());
        // a building's ports pass items and fluids to and from the machines inside
        ItemStorage.SIDED.registerForBlockEntity((port, side) -> port.items(side), BUILDING_PORT.get());
        // a building's controller has no storage of its own on any side: only its ports reach it (PortBlockEntity.items)
        FluidStorage.SIDED.registerForBlockEntity((port, side) -> port.fluids(side), BUILDING_PORT.get());
    }
}
