package com.meakaandre.siftec.compat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meakaandre.siftec.hub.Milestones;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What SIFTEC replaces for good (siftec_content.json "replaced", tools/tables/content.py REPLACED): Create: Gunsmithing's
 * steel is SIFTEC's steel (its steel block nine Steel Ingots, as there is no steel block) and Create's industrial iron
 * is SIFTEC's Steel Casing and steel windows. The old ones are switched off (content.DISABLED, so JEI does not list them
 * either), out of every creative tab, out of every tag ({@code mixin.TagStripMixin}) and every recipe
 * (recipe_tables.REMOVED), and any that still turns up becomes the new one: in a player's inventory, on their cursor
 * and in the container they have open, dropped on the ground, and placed in the world ({@code mixin.LevelChunkMixin},
 * and the blocks of a chunk as it loads). Gunsmithing's own fork is left as it is.
 */
public final class Replaced {
    /** How often players' inventories are looked through, in ticks. */
    private static final int EVERY = 20;
    /** Loaded chunks looked through a tick, at most. */
    private static final int CHUNKS_A_TICK = 16;

    /** An old item's replacement: the new item and how many of it one old item is. */
    public record To(Identifier id, int count) {
    }

    private static final Map<Identifier, To> TABLE = new LinkedHashMap<>();
    private static volatile Map<Item, To> items;
    private static volatile Map<Block, Block> blocks;
    private static final Deque<Queued> CHUNKS = new ArrayDeque<>();

    private record Queued(ResourceKey<Level> dimension, int x, int z) {
    }

    static {
        JsonObject raw = Milestones.raw().getAsJsonObject("replaced");
        if (raw != null) {
            for (Map.Entry<String, JsonElement> e : raw.entrySet()) {
                JsonObject to = e.getValue().getAsJsonObject();
                TABLE.put(Identifier.parse(e.getKey()), new To(Identifier.parse(to.get("id").getAsString()), to.get("count").getAsInt()));
            }
        }
    }

    private Replaced() {
    }

    /** Every replaced id (item or block), with what replaces it. */
    public static Map<Identifier, To> table() {
        return Collections.unmodifiableMap(TABLE);
    }

    /** True if this id is one SIFTEC replaces: tags lose it ({@code mixin.TagStripMixin}). */
    public static boolean replaced(Identifier id) {
        return TABLE.containsKey(id);
    }

    private static Map<Item, To> items() {
        Map<Item, To> map = items;
        if (map != null) return map;
        map = new HashMap<>();
        for (Map.Entry<Identifier, To> e : TABLE.entrySet()) {
            var from = BuiltInRegistries.ITEM.getOptional(e.getKey());
            if (from.isPresent() && BuiltInRegistries.ITEM.getOptional(e.getValue().id()).isPresent()) map.put(from.get(), e.getValue());
        }
        items = map;
        return map;
    }

    private static Map<Block, Block> blocks() {
        Map<Block, Block> map = blocks;
        if (map != null) return map;
        map = new HashMap<>();
        for (Map.Entry<Identifier, To> e : TABLE.entrySet()) {
            var from = BuiltInRegistries.BLOCK.getOptional(e.getKey());
            var to = BuiltInRegistries.BLOCK.getOptional(e.getValue().id());
            if (from.isPresent() && to.isPresent() && e.getValue().count() == 1) map.put(from.get(), to.get());
        }
        blocks = map;
        return map;
    }

    /** True if this item is one SIFTEC replaces. */
    public static boolean isReplaced(Item item) {
        return items().containsKey(item);
    }

    /** True if this block is one SIFTEC replaces with a block of its own. */
    public static boolean isReplaced(Block block) {
        return blocks().containsKey(block);
    }

    /** What a block becomes: the replacement with the same properties, or the state itself. */
    public static BlockState block(BlockState state) {
        if (state == null) return null;
        Block to = blocks().get(state.getBlock());
        return to == null ? state : to.withPropertiesOf(state);
    }

    /**
     * What a stack becomes, as stacks of at most the new item's stack size (a stack of Gunsmithing steel blocks is nine
     * times as many Steel Ingots), or an empty list if the stack is not one SIFTEC replaces. Components stay as they are.
     */
    public static List<ItemStack> convert(ItemStack stack) {
        if (stack.isEmpty()) return List.of();
        To to = items().get(stack.getItem());
        if (to == null) return List.of();
        Item item = BuiltInRegistries.ITEM.getValue(to.id());
        int total = stack.getCount() * to.count();
        List<ItemStack> out = new ArrayList<>();
        ItemStack one = stack.transmuteCopy(item, 1);
        int max = Math.max(1, one.getMaxStackSize());
        while (total > 0) {
            int n = Math.min(max, total);
            out.add(one.copyWithCount(n));
            total -= n;
        }
        return out;
    }

    public static void register() {
        // out of every creative tab, the search tab included
        CreativeModeTabEvents.MODIFY_OUTPUT_ALL.register((tab, output) -> {
            output.getDisplayStacks().removeIf(s -> isReplaced(s.getItem()));
            output.getSearchTabStacks().removeIf(s -> isReplaced(s.getItem()));
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % EVERY == 0) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) convert(player);
            }
            convertChunks(server);
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof ItemEntity drop) convert(drop, level);
        });
        // a chunk saved before (or written by something else) has its old blocks swapped once it is in the world
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            if (!has(chunk)) return;
            synchronized (CHUNKS) {
                CHUNKS.add(new Queued(level.dimension(), chunk.getPos().x(), chunk.getPos().z()));
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            synchronized (CHUNKS) {
                CHUNKS.clear();
            }
        });
    }

    /** Everything a player has that SIFTEC replaces: their inventory, the stack on their cursor and an open container. */
    public static int convert(ServerPlayer player) {
        int changed = 0;
        Inventory inv = player.getInventory();
        List<ItemStack> extra = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            List<ItemStack> to = convert(inv.getItem(i));
            if (to.isEmpty()) continue;
            inv.setItem(i, to.getFirst());
            extra.addAll(to.subList(1, to.size()));
            changed++;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu != null) {
            List<ItemStack> cursor = convert(menu.getCarried());
            if (!cursor.isEmpty()) {
                menu.setCarried(cursor.getFirst());
                extra.addAll(cursor.subList(1, cursor.size()));
                changed++;
            }
            if (menu != player.inventoryMenu) {
                for (Slot slot : menu.slots) {
                    if (slot.container == inv) continue;
                    List<ItemStack> to = convert(slot.getItem());
                    if (to.isEmpty()) continue;
                    slot.set(to.getFirst());
                    extra.addAll(to.subList(1, to.size()));
                    changed++;
                }
            }
            if (changed > 0) menu.broadcastChanges();
        }
        // what does not fit where the old stack was goes into the inventory, or at the player's feet
        for (ItemStack s : extra) {
            if (!inv.add(s) && !s.isEmpty()) player.drop(s, false, Prediction.SERVER_ONLY);
        }
        return changed;
    }

    /** A dropped stack SIFTEC replaces becomes the new one where it lies (more stacks if it no longer fits in one). */
    public static boolean convert(ItemEntity drop, ServerLevel level) {
        List<ItemStack> to = convert(drop.getItem());
        if (to.isEmpty()) return false;
        drop.setItem(to.getFirst());
        for (ItemStack s : to.subList(1, to.size())) {
            ItemEntity more = new ItemEntity(level, drop.getX(), drop.getY(), drop.getZ(), s);
            more.setDeltaMovement(drop.getDeltaMovement());
            level.addFreshEntity(more);
        }
        return true;
    }

    private static boolean has(LevelChunk chunk) {
        Set<Block> old = blocks().keySet();
        if (old.isEmpty()) return false;
        for (LevelChunkSection section : chunk.getSections()) {
            if (section != null && !section.hasOnlyAir() && section.maybeHas(s -> old.contains(s.getBlock()))) return true;
        }
        return false;
    }

    private static void convertChunks(MinecraftServer server) {
        for (int i = 0; i < CHUNKS_A_TICK; i++) {
            Queued next;
            synchronized (CHUNKS) {
                next = CHUNKS.poll();
            }
            if (next == null) return;
            ServerLevel level = server.getLevel(next.dimension());
            if (level == null || !level.hasChunk(next.x(), next.z())) continue;
            convert(level, level.getChunk(next.x(), next.z()));
        }
    }

    /** Every block SIFTEC replaces in a chunk, swapped for its own (same properties); how many were swapped. */
    public static int convert(ServerLevel level, LevelChunk chunk) {
        if (!has(chunk)) return 0;
        int changed = 0;
        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(s -> isReplaced(s.getBlock()))) continue;
            int baseY = level.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!isReplaced(state.getBlock())) continue;
                        level.setBlock(pos.set(baseX + x, baseY + y, baseZ + z), block(state), Block.UPDATE_CLIENTS);
                        changed++;
                    }
        }
        return changed;
    }
}
