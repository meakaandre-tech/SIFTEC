package com.meakaandre.siftec.workshop;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.Containers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What is left of the Workshop's old automatic mode (removed: the Workshop builds by hand only, and machines make
 * the builds instead). A Workshop saved while it held parts fed in by belt, or a finished build in its output slot,
 * still has them here: they go to the first player who opens it, or drop when it is broken. Nothing new goes in.
 */
public class WorkshopBlockEntity extends BlockEntity {
    /** The old automatic mode's save: parts by item id, and the output slot. */
    private static final Codec<Map<Identifier, Integer>> HELD_CODEC = Codec.unboundedMap(Identifier.CODEC, Codec.INT);
    private static final Codec<List<ItemStack>> LEFTOVER_CODEC = ItemStack.CODEC.listOf();

    /** Parts and builds from the old automatic mode, waiting to be given back. */
    private final List<ItemStack> leftover = new ArrayList<>();

    public WorkshopBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** How many items are waiting to be given back; for the selftest. */
    public int leftover() {
        int n = 0;
        for (ItemStack stack : leftover) n += stack.getCount();
        return n;
    }

    /** Gives the leftovers to the player (what does not fit drops at their feet). Returns true if there were any. */
    public boolean giveBack(ServerPlayer player) {
        if (leftover.isEmpty()) return false;
        for (ItemStack stack : leftover) player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
        leftover.clear();
        setChanged();
        return true;
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level == null) return;
        for (ItemStack stack : leftover) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
        leftover.clear();
    }

    @Override
    protected void saveAdditional(ValueOutput view) {
        super.saveAdditional(view);
        if (!leftover.isEmpty()) view.store("Leftover", LEFTOVER_CODEC, leftover);
    }

    @Override
    protected void loadAdditional(ValueInput view) {
        super.loadAdditional(view);
        leftover.clear();
        leftover.addAll(view.read("Leftover", LEFTOVER_CODEC).orElse(List.of()));
        // a Workshop saved by the automatic mode: its parts ("Held") and its output slot ("Output")
        for (Map.Entry<Identifier, Integer> e : view.read("Held", HELD_CODEC).orElse(Map.of()).entrySet()) {
            Item item = BuiltInRegistries.ITEM.getOptional(e.getKey()).orElse(Items.AIR);
            int n = e.getValue();
            while (item != Items.AIR && n > 0) {
                int take = Math.min(n, item.getDefaultMaxStackSize());
                leftover.add(new ItemStack(item, take));
                n -= take;
            }
        }
        ItemStack output = view.read("Output", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);
        if (!output.isEmpty()) leftover.add(output);
    }
}
