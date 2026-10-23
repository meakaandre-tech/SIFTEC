package com.meakaandre.siftec.blueprint;

import com.meakaandre.siftec.backpack.Carried;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.claim.ClaimMarkerBlock;
import com.meakaandre.siftec.claim.Claims;
import com.meakaandre.siftec.equip.Ziplines;
import com.meakaandre.siftec.hub.HubBlock;
import com.meakaandre.siftec.hub.Locks;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.mixin.BlockDisplayInvoker;
import com.meakaandre.siftec.mixin.DisplayInvoker;
import com.meakaandre.siftec.node.NodeSites;
import com.meakaandre.siftec.prefab.Prefabs;
import com.meakaandre.siftec.registry.ModItems;
import com.mojang.math.Transformation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** Saves the cube of {@code 2 * reach + 1} blocks each way sitting on top of {@code below}, centred on it. */
    public static ItemStack save(Level level, BlockPos below, int reach) {
        return save(level, new AABB(below.getX() - reach, below.getY() + 1, below.getZ() - reach, below.getX() + reach + 1, below.getY() + 2 + 2 * reach, below.getZ() + reach + 1));
    }

    /** Saves what stands in a cubic box (a Blueprint Designer's volume) as a Blueprint; empty if nothing does. */
    public static ItemStack save(Level level, AABB box) {
        int side = (int) Math.round(box.getXsize());
        BlockPos designer = BlockPos.containing(box.minX, box.minY, box.minZ);
        List<BlockState> palette = new ArrayList<>();
        int[] cells = new int[side * side * side];
        int blocks = 0;
        for (int y = 0; y < side; y++) for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) {
            BlockState state = level.getBlockState(new BlockPos((int) box.minX + x, (int) box.minY + y, (int) box.minZ + z));
            int index = 0;
            if (!state.isAir() && state.getBlock().asItem() != Items.AIR && state.getDestroySpeed(level, designer) >= 0 && copyable(state)) {
                state = tidy(state);
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

    /** Blocks that claim land or are a designer themselves are left out of a blueprint. */
    private static boolean copyable(BlockState state) {
        Block block = state.getBlock();
        return !(block instanceof HubBlock || block instanceof ClaimMarkerBlock || block instanceof DesignerBlock);
    }

    private static final Set<String> FRESH = Set.of("age", "level", "honey_level", "charges", "moisture", "stage", "berries",
        "has_bottle_0", "has_bottle_1", "has_bottle_2", "has_book", "has_record", "dusted", "hatch", "bites", "extended",
        // running state, not shape: a furnace is not burning, a lever or button is not pressed, a dispenser has not fired
        "lit", "powered", "triggered", "power");
    private static final Set<String> COUNTS = Set.of("candles", "pickles", "eggs", "flower_amount", "segment_amount", "layers");

    private static <T extends Comparable<T>> BlockState fresh(BlockState state, Property<T> property) {
        return state.setValue(property, state.getBlock().defaultBlockState().getValue(property));
    }

    /**
     * A blueprint keeps the shape and the way a block faces, not what has grown or been put in it or what it is
     * doing: crops go back to seedlings, cauldrons and composters are empty, nothing holds water, furnaces are out.
     */
    public static BlockState tidy(BlockState state) {
        if (state.getBlock() instanceof AbstractCauldronBlock) return Blocks.CAULDRON.defaultBlockState();
        for (Property<?> property : state.getProperties()) {
            if (FRESH.contains(property.getName())) state = fresh(state, property);
        }
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            state = state.setValue(BlockStateProperties.WATERLOGGED, false);
        }
        return state;
    }

    /** How many of its item a block costs: none for the top of a door or the head of a bed, two for a double slab, one per candle. */
    private static int price(BlockState state) {
        int count = 1;
        for (Property<?> property : state.getProperties()) {
            Object value = state.getValue(property);
            if (value == DoubleBlockHalf.UPPER || value == BedPart.HEAD) return 0;
            if (value == SlabType.DOUBLE) count = 2;
            if (value instanceof Integer amount && COUNTS.contains(property.getName())) count = amount;
        }
        return count;
    }

    private static CompoundTag data(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag().getCompoundOrEmpty("siftec_blueprint");
    }

    /** Turns the blueprint a quarter turn clockwise and says which way it now faces (0 to 3 quarter turns). */
    public static int rotate(ServerPlayer player, ItemStack stack) {
        int turns = (data(stack).getIntOr("turns", 0) + 1) % 4;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> {
            CompoundTag tag = root.getCompoundOrEmpty("siftec_blueprint");
            tag.putInt("turns", turns);
            root.put("siftec_blueprint", tag);
        });
        PREVIEW.remove(player.getUUID());
        clearGhosts(player.getUUID());
        return turns;
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
        int turns = tag.getIntOr("turns", 0) & 3;
        Rotation rotation = Rotation.values()[turns];
        for (int y = 0; y < side; y++) for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) {
            int index = cells[(y * side + z) * side + x];
            if (index <= 0 || index > palette.size() || palette.get(index - 1).isAir()) continue;
            // each quarter turn clockwise sends (x, z) to (-z, x) about the centre column
            int dx = x - half, dz = z - half;
            for (int t = 0; t < turns; t++) {
                int was = dx;
                dx = -dz;
                dz = was;
            }
            out.put(base.offset(dx, y, dz), tidy(palette.get(index - 1)).rotate(rotation));
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

    /** The ghost blocks each player is being shown, and the game time they vanish. */
    private static final Map<UUID, List<Display>> GHOSTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> GHOSTS_UNTIL = new ConcurrentHashMap<>();
    private static final int MAX_GHOSTS = 1500;
    private static final float GHOST_SCALE = 0.8f;

    private static void clearGhosts(UUID player) {
        List<Display> old = GHOSTS.remove(player);
        if (old != null) for (Display ghost : old) ghost.discard();
        GHOSTS_UNTIL.remove(player);
    }

    /** The hologram: every block of the blueprint drawn a little small, where it would go. It fades after half a minute. */
    private static void showGhosts(ServerLevel level, ServerPlayer player, Map<BlockPos, BlockState> layout) {
        clearGhosts(player.getUUID());
        List<Display> ghosts = new ArrayList<>();
        float inset = (1f - GHOST_SCALE) / 2f;
        Transformation shape = new Transformation(new Vector3f(inset, inset, inset), new Quaternionf(),
            new Vector3f(GHOST_SCALE, GHOST_SCALE, GHOST_SCALE), new Quaternionf());
        for (Map.Entry<BlockPos, BlockState> entry : layout.entrySet()) {
            if (ghosts.size() >= MAX_GHOSTS) break;
            Display.BlockDisplay ghost = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.TRIGGERED);
            if (ghost == null) break;
            ((BlockDisplayInvoker) ghost).siftec$show(entry.getValue());
            ((DisplayInvoker) ghost).siftec$shape(shape);
            ghost.setPos(entry.getKey().getX(), entry.getKey().getY(), entry.getKey().getZ());
            level.addFreshEntity(ghost);
            ghost.addTag(Ziplines.TAG);
            ghosts.add(ghost);
        }
        GHOSTS.put(player.getUUID(), ghosts);
        GHOSTS_UNTIL.put(player.getUUID(), level.getGameTime() + CONFIRM_TICKS);
    }

    /** Called every server tick: holograms whose time is up, or whose player has gone, are taken down. */
    public static void tick(MinecraftServer server) {
        if (GHOSTS.isEmpty() || server.getTickCount() % 20 != 0) return;
        long now = server.overworld().getGameTime();
        for (UUID id : List.copyOf(GHOSTS.keySet())) {
            if (server.getPlayerList().getPlayer(id) == null || GHOSTS_UNTIL.getOrDefault(id, 0L) <= now) clearGhosts(id);
        }
    }

    public static void clear() {
        GHOSTS.clear();
        GHOSTS_UNTIL.clear();
        PREVIEW.clear();
    }

    /**
     * True for a spot no blueprint may build into, empty or not: an ore node's position (grown or mined, it grows back
     * there), a block of a Build Gun building (a doorway or the room inside included) or of the HUB's shell.
     */
    public static boolean reserved(ServerLevel level, BlockPos pos) {
        return NodeSites.isSite(level, pos) || Buildings.within(level, pos)
            || Prefabs.isPart(level.getBlockState(pos));
    }

    /**
     * Used on a block: the first use puts up a hologram of the blueprint where it would stand and says what is
     * missing; a second use on the same spot builds it, taking every block from the player's inventory and backpack.
     */
    public static void use(ServerPlayer player, ItemStack stack, BlockPos base) {
        ServerLevel level = player.level();
        Map<BlockPos, BlockState> layout = layout(level, stack, base);
        if (layout.isEmpty()) {
            player.sendOverlayMessage(Component.translatable("siftec.blueprint.blank"));
            return;
        }
        long[] last = PREVIEW.get(player.getUUID());
        long now = level.getGameTime();
        boolean second = last != null && last[0] == base.asLong() && now - last[1] <= CONFIRM_TICKS;
        if (!second) {
            outline(level, player, base, data(stack).getIntOr("side", 1));
            showGhosts(level, player, layout);
            PREVIEW.put(player.getUUID(), new long[]{base.asLong(), now});
        }
        Map<Item, Integer> need = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : layout.entrySet()) {
            if (!level.getBlockState(entry.getKey()).canBeReplaced() || !Claims.allowed(player, level, entry.getKey()) || reserved(level, entry.getKey())) {
                player.sendOverlayMessage(Component.translatable("siftec.blueprint.blocked", entry.getKey().getX(), entry.getKey().getY(), entry.getKey().getZ()));
                return;
            }
            int price = price(entry.getValue());
            if (price > 0) need.merge(entry.getValue().getBlock().asItem(), price, Integer::sum);
        }
        for (Item item : need.keySet()) {
            if (Locks.allowed(player, new ItemStack(item))) continue;
            Milestone lock = Locks.lockOf(item);
            player.sendOverlayMessage(Component.translatable("siftec.lock.item", lock == null ? "?" : lock.name()));
            return;
        }
        // the blocks come from the inventory and the backpack alike
        Carried inventory = Carried.of(player);
        boolean missing = false;
        if (!player.hasInfiniteMaterials()) {
            for (Map.Entry<Item, Integer> entry : need.entrySet()) {
                int have = 0;
                for (int i = 0; i < inventory.getContainerSize(); i++) if (inventory.getItem(i).is(entry.getKey())) have += inventory.getItem(i).getCount();
                if (have < entry.getValue()) {
                    player.sendSystemMessage(Component.translatable("siftec.blueprint.missing", entry.getValue() - have, new ItemStack(entry.getKey()).getItemName()));
                    missing = true;
                }
            }
        }
        if (missing) return;
        if (!second) {
            player.sendOverlayMessage(Component.translatable("siftec.blueprint.confirm", layout.size()));
            return;
        }
        PREVIEW.remove(player.getUUID());
        clearGhosts(player.getUUID());
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
        // machines, ports and portals learn whose they are, the same as when placed by hand
        for (Map.Entry<BlockPos, BlockState> entry : layout.entrySet()) {
            BlockState state = entry.getValue();
            if (state.hasBlockEntity() && level.getBlockState(entry.getKey()) == state) {
                state.getBlock().setPlacedBy(level, entry.getKey(), state, player, new ItemStack(state.getBlock().asItem()));
            }
        }
        player.sendOverlayMessage(Component.translatable("siftec.blueprint.built", layout.size()));
    }
}
