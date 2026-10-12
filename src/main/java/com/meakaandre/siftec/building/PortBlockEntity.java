package com.meakaandre.siftec.building;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.FilteringStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * A port in the world: the cell inside the building it feeds or drains ({@code target}) and its controller. Only its
 * outward face takes or gives anything, and only the kind it is (items or fluids, in or out).
 */
public class PortBlockEntity extends BlockEntity {
    public @Nullable BlockPos target;
    public @Nullable BlockPos controller;

    public PortBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    private boolean serves(@Nullable Direction side, boolean fluid) {
        BlockState state = getBlockState();
        if (level == null || target == null || !state.hasProperty(PortBlock.KIND)) return false;
        if (state.getValue(PortBlock.KIND).fluid() != fluid) return false;
        if (side != null && side != state.getValue(PortBlock.FACING)) return false;
        // a port never passes things on to another port (no loops)
        return !(level.getBlockState(target).getBlock() instanceof PortBlock);
    }

    public @Nullable Storage<ItemVariant> items(@Nullable Direction side) {
        if (!serves(side, false)) return null;
        Storage<ItemVariant> inner = ItemStorage.SIDED.find(level, target, getBlockState().getValue(PortBlock.FACING));
        if (inner == null) return null;
        return getBlockState().getValue(PortBlock.KIND).input() ? FilteringStorage.insertOnlyOf(inner) : FilteringStorage.extractOnlyOf(inner);
    }

    public @Nullable Storage<FluidVariant> fluids(@Nullable Direction side) {
        if (!serves(side, true)) return null;
        Storage<FluidVariant> inner = FluidStorage.SIDED.find(level, target, getBlockState().getValue(PortBlock.FACING));
        if (inner == null) return null;
        return getBlockState().getValue(PortBlock.KIND).input() ? FilteringStorage.insertOnlyOf(inner) : FilteringStorage.extractOnlyOf(inner);
    }

    @Override
    protected void saveAdditional(ValueOutput view) {
        super.saveAdditional(view);
        if (target != null) view.store("Feeds", BlockPos.CODEC, target);
        if (controller != null) view.store("Controller", BlockPos.CODEC, controller);
    }

    @Override
    protected void loadAdditional(ValueInput view) {
        super.loadAdditional(view);
        target = view.read("Feeds", BlockPos.CODEC).orElse(null);
        controller = view.read("Controller", BlockPos.CODEC).orElse(null);
    }
}
