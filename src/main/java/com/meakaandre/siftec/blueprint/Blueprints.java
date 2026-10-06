package com.meakaandre.siftec.blueprint;

import com.meakaandre.siftec.claim.Claims;
import com.meakaandre.siftec.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Saving what stands in a designer's volume into a Blueprint item, and building it again somewhere else.
 * A blueprint keeps which block goes where; it does not keep what was inside chests or machines.
 */
public final class Blueprints {
    /** The last spot each player previewed a blueprint at, and when. Using it there again builds it. */
    private static final Map<UUID, long[]> PREVIEW = new ConcurrentHashMap<>();
    private static final int CONFIRM_TICKS = 20 * 30;

    private Blueprints() {
    }

    public static ItemStack save(Level level, BlockPos designer, int reach) {
        AABB box = DesignerBlockEntity.volume(designer, reach);
        int side = 2 * reach + 1;
        List<BlockState> palette = new ArrayList<>();
        int[] cells = new int[side * side * side];
        int blocks = 0;
        for (int y = 0; y < side; y++) for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) {
            BlockState state = level.getBlockState(new BlockPos((int) box.minX + x, (int) box.minY + y, (int) box.minZ + z));
            int index = 0;
            if (!state.isAir() && state.getBlock().asItem() != Items.AIR && state.getDestroySpeed(level, designer) >= 0) {
                index = palette.indexOf(state) + 1;
                if (index == 0) {
                    palette.add(state);
                    index = palette.size();
                }
                blocks++;
            }
            cells[(y * side + z) * side + x] = index;
        }
        if (blocks == 0) return ItemStack.EMPTY;
        CompoundTag tag = new CompoundTag();
        ListTag states = new ListTag();
        for (BlockState state : palette) states.add(NbtUtils.writeBlockState(state));
        tag.put("palette", states);
        tag.putIntArray("cells", cells);
        tag.putInt("side", side);
        tag.putInt("blocks", blocks);
        ItemStack stack = new ItemStack(ModItems.BLUEPRINT.get());
        CompoundTag root = new CompoundTag();
        root.put("siftec_blueprint", tag);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        return stack;
    }

    private static CompoundTag data(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag().getCompoundOrEmpty("siftec_blueprint");
    }

    public static int count(ItemStack stack) {
        return data(stack).getIntOr("blocks", 0);
    }

    /** Every block of the blueprint with its bottom centre at the given spot. */
    private static Map<BlockPos, BlockState> layout(ServerLevel level, ItemStack stack, BlockPos base) {
        CompoundTag tag = data(stack);
        int side = tag.getIntOr("side", 0);
        int[] cells = tag.getIntArray("cells").orElse(new int[0]);
        ListTag states = tag.getListOrEmpty("palette");
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        if (side <= 0 || cells.length != side * side * side) return out;
        HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < states.size(); i++) palette.add(NbtUtils.readBlockState(blocks, states.getCompoundOrEmpty(i)));
        int half = side / 2;
        for (int y = 0; y < side; y++) for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) {
            int index = cells[(y * side + z) * side + x];
            if (index <= 0 || index > palette.size() || palette.get(index - 1).isAir()) continue;
            out.put(base.offset(x - half, y, z - half), palette.get(index - 1));
        }
        return out;
    }

    private static void outline(ServerLevel level, ServerPlayer player, BlockPos base, int side) {
        int half = side / 2;
        AABB box = new AABB(base.getX() - half, base.getY(), base.getZ() - half, base.getX() + half + 1, base.getY() + side, base.getZ() + half + 1);
        for (double t = 0; t <= 1.0001; t += 1.0 / side) {
            for (int i = 0; i < 4; i++) {
                double a = (i & 1) == 0 ? 0 : 1, b = (i & 2) == 0 ? 0 : 1;
                level.sendParticles(player, ParticleTypes.HAPPY_VILLAGER, true, false, box.minX + box.getXsize() * t, box.minY + box.getYsize() * a, box.minZ + box.getZsize() * b, 1, 0, 0, 0, 0);
                level.sendParticles(player, ParticleTypes.HAPPY_VILLAGER, true, false, box.minX + box.getXsize() * a, box.minY + box.getYsize() * t, box.minZ + box.getZsize() * b, 1, 0, 0, 0, 0);
                level.sendParticles(player, ParticleTypes.HAPPY_VILLAGER, true, false, box.minX + box.getXsize() * a, box.minY + box.getYsize() * b, box.minZ + box.getZsize() * t, 1, 0, 0, 0, 0);
            }
        }
    }

    /**
     * Used on a block: the first use shows where the blueprint would stand and what is missing; a second use on
     * the same spot builds it, taking every block from the player's inventory.
     */
    public static void use(ServerPlayer player, ItemStack stack, BlockPos base) {
        ServerLevel level = player.level();
        Map<BlockPos, BlockState> layout = layout(level, stack, base);
        if (layout.isEmpty()) {
            player.sendOverlayMessage(Component.translatable("siftec.blueprint.blank"));
            return;
        }
        outline(level, player, base, data(stack).getIntOr("side", 1));
        Map<Item, Integer> need = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : layout.entrySet()) {
            if (!level.getBlockState(entry.getKey()).canBeReplaced() || !Claims.allowed(player, level, entry.getKey())) {
                player.sendOverlayMessage(Component.translatable("siftec.blueprint.blocked", entry.getKey().getX(), entry.getKey().getY(), entry.getKey().getZ()));
                return;
            }
            need.merge(entry.getValue().getBlock().asItem(), 1, Integer::sum);
        }
        Inventory inventory = player.getInventory();
        if (!player.hasInfiniteMaterials()) {
            for (Map.Entry<Item, Integer> entry : need.entrySet()) {
                int have = 0;
                for (int i = 0; i < inventory.getContainerSize(); i++) if (inventory.getItem(i).is(entry.getKey())) have += inventory.getItem(i).getCount();
                if (have < entry.getValue()) {
                    player.sendSystemMessage(Component.translatable("siftec.blueprint.missing", entry.getValue() - have, new ItemStack(entry.getKey()).getItemName()));
                    PREVIEW.remove(player.getUUID());
                    return;
                }
            }
        }
        long[] last = PREVIEW.get(player.getUUID());
        long now = level.getGameTime();
        if (last == null || last[0] != base.asLong() || now - last[1] > CONFIRM_TICKS) {
            PREVIEW.put(player.getUUID(), new long[]{base.asLong(), now});
            player.sendOverlayMessage(Component.translatable("siftec.blueprint.confirm", layout.size()));
            return;
        }
        PREVIEW.remove(player.getUUID());
        if (!player.hasInfiniteMaterials()) {
            for (Map.Entry<Item, Integer> entry : need.entrySet()) {
                int left = entry.getValue();
                for (int i = 0; i < inventory.getContainerSize() && left > 0; i++) {
                    if (!inventory.getItem(i).is(entry.getKey())) continue;
                    int take = Math.min(left, inventory.getItem(i).getCount());
                    inventory.removeItem(i, take);
                    left -= take;
                }
            }
        }
        for (Map.Entry<BlockPos, BlockState> entry : layout.entrySet()) level.setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_ALL);
        player.sendOverlayMessage(Component.translatable("siftec.blueprint.built", layout.size()));
    }
}
