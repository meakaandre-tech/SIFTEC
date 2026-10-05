package com.meakaandre.siftec.block;

import com.meakaandre.siftec.node.Node;
import com.meakaandre.siftec.node.NodeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

/** What both kinds of miner share. */
public final class Mining {
    private Mining() {
    }

    /** The node a miner at {@code pos} is standing on: it has to be on the mound's middle block. */
    public static Optional<Node> nodeUnder(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server)) return Optional.empty();
        BlockState below = level.getBlockState(pos.below());
        if (!(below.getBlock() instanceof NodeBlock node) || !below.getValue(NodeBlock.CORE)) return Optional.empty();
        Optional<Node> found = NodeMap.near(server, pos.getX(), pos.getZ(), 0);
        return found.filter(n -> n.type() == node.type);
    }

    /** Adds one item to the output slot. False if it is full. */
    public static boolean produce(OutputContainer output, Node node) {
        Item item = node.type().output();
        if (item == Items.AIR) return false;
        ItemStack stack = output.get();
        if (stack.isEmpty()) {
            output.set(new ItemStack(item));
            return true;
        }
        if (!stack.is(item) || stack.getCount() >= stack.getMaxStackSize()) return false;
        stack.grow(1);
        return true;
    }

    public static boolean isFull(OutputContainer output) {
        ItemStack stack = output.get();
        return !stack.isEmpty() && stack.getCount() >= stack.getMaxStackSize();
    }

    public static void give(OutputContainer output, Player player) {
        ItemStack stack = output.get();
        if (stack.isEmpty()) return;
        output.set(ItemStack.EMPTY);
        player.getInventory().placeItemBackInInventory(stack, net.minecraft.util.Prediction.SERVER_ONLY);
    }
}
