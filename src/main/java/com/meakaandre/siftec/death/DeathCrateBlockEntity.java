package com.meakaandre.siftec.death;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What a Death Crate holds: every stack its player carried, each with the place it came from (the slot of the
 * inventory, or of the backpack's storage or tool slots), so it goes back exactly there. Also who it belongs to and
 * the map marker that shows it.
 */
public class DeathCrateBlockEntity extends BlockEntity {
    /** Where a stack came from: the player's inventory (hotbar, main, armour, offhand) or a part of their backpack. */
    public static final int INVENTORY = 0;

    /** One stack and where it goes back: {@code where} is INVENTORY or 1 + the backpack container's index. */
    public record Entry(int where, int slot, ItemStack stack) {
    }

    public UUID owner;
    public String ownerName = "";
    /**
     * A building's crate ({@link DeathCrates#stash}): the company whose building came down. Any of its members opens it,
     * and its stacks go anywhere in their inventory. Empty for a player's Death Crate.
     */
    public String companyId = "";
    public String markerId = "";
    public final List<Entry> entries = new ArrayList<>();

    public DeathCrateBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Taken away by something other than its owner emptying it (a creative player, a command): nothing is lost. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level instanceof net.minecraft.server.level.ServerLevel server) DeathCrates.removed(server, this);
    }

    @Override
    protected void saveAdditional(ValueOutput view) {
        super.saveAdditional(view);
        if (owner != null) view.store("Owner", UUIDUtil.CODEC, owner);
        view.putString("OwnerName", ownerName);
        if (!companyId.isEmpty()) view.putString("Company", companyId);
        view.putString("Marker", markerId);
        List<Integer> where = new ArrayList<>(), slots = new ArrayList<>();
        List<ItemStack> stacks = new ArrayList<>();
        for (Entry e : entries) {
            where.add(e.where());
            slots.add(e.slot());
            stacks.add(e.stack());
        }
        view.store("Where", Codec.INT.listOf(), where);
        view.store("Slots", Codec.INT.listOf(), slots);
        view.store("Items", ItemStack.OPTIONAL_CODEC.listOf(), stacks);
    }

    @Override
    protected void loadAdditional(ValueInput view) {
        super.loadAdditional(view);
        owner = view.read("Owner", UUIDUtil.CODEC).orElse(null);
        ownerName = view.getStringOr("OwnerName", "");
        companyId = view.getStringOr("Company", "");
        markerId = view.getStringOr("Marker", "");
        List<Integer> where = view.read("Where", Codec.INT.listOf()).orElse(List.of());
        List<Integer> slots = view.read("Slots", Codec.INT.listOf()).orElse(List.of());
        List<ItemStack> stacks = view.read("Items", ItemStack.OPTIONAL_CODEC.listOf()).orElse(List.of());
        entries.clear();
        int n = Math.min(where.size(), Math.min(slots.size(), stacks.size()));
        for (int i = 0; i < n; i++) {
            if (!stacks.get(i).isEmpty()) entries.add(new Entry(where.get(i), slots.get(i), stacks.get(i)));
        }
    }
}
