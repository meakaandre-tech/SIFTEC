package com.meakaandre.siftec.prefab;

import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.List;

/** The Personal Storage Box's 27 slots and the company it belongs to. */
public class StorageBoxBlockEntity extends BlockEntity {
    public static final int SIZE = 27;
    public String companyId = "";
    public final SimpleContainer items = new SimpleContainer(SIZE) {
        @Override
        public void setChanged() {
            super.setChanged();
            StorageBoxBlockEntity.this.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return !isRemoved() && level != null && level.getBlockEntity(worldPosition) == StorageBoxBlockEntity.this
                && player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) <= 64;
        }
    };

    public StorageBoxBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** Takes everything out (for picking the HUB up): the box is left empty, so nothing can drop twice. */
    public List<ItemStack> takeAll() {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) {
            ItemStack stack = items.removeItemNoUpdate(i);
            if (!stack.isEmpty()) out.add(stack);
        }
        setChanged();
        return out;
    }

    @Override
    protected void saveAdditional(ValueOutput view) {
        super.saveAdditional(view);
        view.putString("Company", companyId);
        List<ItemStack> slots = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) slots.add(items.getItem(i));
        view.store("Items", ItemStack.OPTIONAL_CODEC.listOf(), slots);
    }

    @Override
    protected void loadAdditional(ValueInput view) {
        super.loadAdditional(view);
        companyId = view.getStringOr("Company", "");
        List<ItemStack> slots = view.read("Items", ItemStack.OPTIONAL_CODEC.listOf()).orElse(List.of());
        for (int i = 0; i < SIZE; i++) items.setItem(i, i < slots.size() ? slots.get(i) : ItemStack.EMPTY);
    }
}
