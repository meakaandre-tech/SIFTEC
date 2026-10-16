package com.meakaandre.siftec.death;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.map.MapServer;
import com.meakaandre.siftec.registry.ModBlocks;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Satisfactory's death crate (Exploration and Equipment tab, Backpack). When a player dies, everything they carry goes
 * into a Death Crate where they died: the hotbar, the rest of the inventory, armour, offhand and everything in their
 * backpack (its storage and tool slots). They keep the backpack and its upgrades. The crate is a block: it never
 * despawns, cannot be broken, blown up or pushed, and the map shows it as the player's death marker. Clicking it
 * puts every stack straight back in the slot it came from; what cannot go back (the slot has something in it now)
 * goes anywhere in the inventory or backpack, and what does not fit at all stays in the crate.
 * <p>
 * Nothing changes with the keepInventory game rule on (nothing is dropped, so there is no crate), Curse of
 * Vanishing items vanish as in vanilla, and the Build Gun stays out of it (every player is given one again when they
 * respawn). Packing happens where vanilla would drop the inventory
 * (mixin/PlayerDeathCrateMixin).
 */
public final class DeathCrates {
    /** The parts of the worn backpack a crate takes and gives back (set by the Traveler's Backpack compat layer). */
    public interface BackpackAccess {
        /** The backpack's containers, in a fixed order (storage, then tool slots). */
        List<Container> containers(ServerPlayer player);

        /** After a crate gave things back (the backpack is checked again). */
        default void afterRestore(ServerPlayer player) {
        }
    }

    public static volatile BackpackAccess backpack = null;
    /** Players whose death this tick left a crate (the crate carries the death marker, so no second one is placed). */
    private static final Set<UUID> JUST_PLACED = new HashSet<>();

    private DeathCrates() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> JUST_PLACED.clear());
    }

    public static boolean placedJustNow(ServerPlayer player) {
        return JUST_PLACED.contains(player.getUUID());
    }

    private static List<Container> backpackContainers(ServerPlayer player) {
        BackpackAccess access = backpack;
        return access == null ? List.of() : access.containers(player);
    }

    /**
     * Takes everything the player carries out of the inventory and the backpack, each stack with where it was. With
     * {@code take} false it only looks (for the selftests).
     */
    public static List<DeathCrateBlockEntity.Entry> carried(ServerPlayer player, boolean take) {
        List<DeathCrateBlockEntity.Entry> out = new ArrayList<>();
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            if (take) inventory.setItem(i, ItemStack.EMPTY);
            // the Build Gun is every player's from the start and comes back with them when they respawn (prefab/Prefabs)
            if (isBuildGun(stack)) continue;
            out.add(new DeathCrateBlockEntity.Entry(DeathCrateBlockEntity.INVENTORY, i, stack.copy()));
        }
        List<Container> parts = backpackContainers(player);
        for (int k = 0; k < parts.size(); k++) {
            Container part = parts.get(k);
            for (int j = 0; j < part.getContainerSize(); j++) {
                ItemStack stack = part.getItem(j);
                if (stack.isEmpty()) continue;
                out.add(new DeathCrateBlockEntity.Entry(1 + k, j, stack.copy()));
                if (take) part.setItem(j, ItemStack.EMPTY);
            }
        }
        return out;
    }

    /**
     * At death: packs everything the player carries into a crate where they died, and marks it on their map. Returns
     * where the crate is, or null when they carried nothing.
     */
    public static BlockPos pack(ServerPlayer player) {
        if (Companies.isFake(player)) return null;
        List<DeathCrateBlockEntity.Entry> entries = carried(player, true);
        if (entries.isEmpty()) return null;
        ServerLevel level = player.level();
        BlockPos pos = spot(level, player.blockPosition());
        level.setBlockAndUpdate(pos, ModBlocks.DEATH_CRATE.get().defaultBlockState());
        if (!(level.getBlockEntity(pos) instanceof DeathCrateBlockEntity crate)) {
            // the crate could not be put down (nothing should stop it): drop everything as vanilla would
            for (DeathCrateBlockEntity.Entry e : entries) Containers.dropItemStack(level, player.getX(), player.getY(), player.getZ(), e.stack());
            Siftec.LOGGER.warn("SIFTEC: could not put {}'s Death Crate down at {}; dropped their things", player.getGameProfile().name(), pos);
            return null;
        }
        crate.owner = player.getUUID();
        crate.ownerName = player.getGameProfile().name();
        crate.entries.addAll(entries);
        crate.markerId = MapServer.crateMarker(player, level, pos);
        crate.setChanged();
        JUST_PLACED.add(player.getUUID());
        player.sendSystemMessage(Component.translatable("siftec.crate.placed", pos.getX(), pos.getY(), pos.getZ()).withStyle(ChatFormatting.GOLD));
        Siftec.LOGGER.info("SIFTEC: {} died; {} stacks went into a Death Crate at {} {}", crate.ownerName, entries.size(), level.dimension().identifier(), pos);
        return pos;
    }

    /** Where the crate goes: where the player died, or the nearest free spot above it (below, when there is none). */
    static BlockPos spot(ServerLevel level, BlockPos at) {
        int minY = level.getMinY(), maxY = level.getMaxY();
        if (at.getY() <= minY) {
            // fell out of the world: the ground of that column, if it has any
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ());
            at = at.atY(top > minY ? top : minY + 1);
        }
        if (at.getY() >= maxY) at = at.atY(maxY - 1);
        for (int y = at.getY(); y < maxY; y++) {
            if (free(level, at.atY(y))) return at.atY(y);
        }
        for (int y = at.getY() - 1; y > minY; y--) {
            if (free(level, at.atY(y))) return at.atY(y);
        }
        return at;
    }

    private static boolean free(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || (state.canBeReplaced() && !state.hasBlockEntity());
    }

    /** The owner clicked their crate: everything goes back. Anyone else is told whose it is. */
    public static void open(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.level();
        if (!(level.getBlockEntity(pos) instanceof DeathCrateBlockEntity crate)) return;
        if (crate.owner != null && !crate.owner.equals(player.getUUID())) {
            player.sendOverlayMessage(Component.translatable("siftec.crate.not_yours", crate.ownerName));
            return;
        }
        restore(player, crate);
    }

    /**
     * Puts the crate's stacks back: each into the slot it came from when that slot is free, the rest anywhere in the
     * inventory, then the backpack. What still does not fit stays in the crate; an emptied crate goes away with its
     * marker. Returns how many stacks stayed in the crate.
     */
    public static int restore(ServerPlayer player, DeathCrateBlockEntity crate) {
        ServerLevel level = player.level();
        Inventory inventory = player.getInventory();
        List<Container> parts = backpackContainers(player);
        boolean wasEmpty = crate.isEmpty();
        // the Build Gun the player got back when they respawned moves out of a slot the crate has something for
        Set<Integer> wanted = new HashSet<>();
        for (DeathCrateBlockEntity.Entry e : crate.entries) if (e.where() == DeathCrateBlockEntity.INVENTORY) wanted.add(e.slot());
        for (int i : wanted) {
            if (i < 0 || i >= Inventory.INVENTORY_SIZE || !isBuildGun(inventory.getItem(i))) continue;
            for (int j = 0; j < Inventory.INVENTORY_SIZE; j++) {
                if (wanted.contains(j) || !inventory.getItem(j).isEmpty()) continue;
                inventory.setItem(j, inventory.getItem(i));
                inventory.setItem(i, ItemStack.EMPTY);
                break;
            }
        }
        List<DeathCrateBlockEntity.Entry> later = new ArrayList<>();
        for (DeathCrateBlockEntity.Entry e : crate.entries) {
            Container target = e.where() == DeathCrateBlockEntity.INVENTORY ? inventory : (e.where() - 1 < parts.size() ? parts.get(e.where() - 1) : null);
            if (target != null && e.slot() >= 0 && e.slot() < target.getContainerSize() && target.getItem(e.slot()).isEmpty()
                && target.canPlaceItem(e.slot(), e.stack())) {
                target.setItem(e.slot(), e.stack().copy());
            } else {
                later.add(e);
            }
        }
        List<DeathCrateBlockEntity.Entry> left = new ArrayList<>();
        for (DeathCrateBlockEntity.Entry e : later) {
            ItemStack stack = e.stack().copy();
            inventory.add(stack);
            for (Container part : parts) if (!stack.isEmpty()) insert(part, stack);
            if (!stack.isEmpty()) left.add(new DeathCrateBlockEntity.Entry(e.where(), e.slot(), stack));
        }
        inventory.setChanged();
        crate.entries.clear();
        crate.entries.addAll(left);
        if (left.isEmpty()) {
            MapServer.removeMarker(level.getServer(), crate.markerId);
            crate.markerId = "";
            level.removeBlock(crate.getBlockPos(), false);
            player.sendOverlayMessage(Component.translatable(wasEmpty ? "siftec.crate.empty" : "siftec.crate.restored").withStyle(ChatFormatting.GOLD));
        } else {
            crate.setChanged();
            player.sendOverlayMessage(Component.translatable("siftec.crate.partly", left.size()).withStyle(ChatFormatting.YELLOW));
        }
        player.containerMenu.broadcastChanges();
        BackpackAccess access = backpack;
        if (access != null) access.afterRestore(player);
        Siftec.LOGGER.info("SIFTEC: {} opened their Death Crate at {}; {} stacks stay in it", player.getGameProfile().name(), crate.getBlockPos(), left.size());
        return left.size();
    }

    private static boolean isBuildGun(ItemStack stack) {
        return stack.getItem() instanceof com.meakaandre.siftec.building.BuildGunItem;
    }

    /** Merges the stack into the container's matching stacks, then its empty slots, as far as it goes. */
    private static void insert(Container container, ItemStack stack) {
        for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack there = container.getItem(i);
            if (there.isEmpty() || !ItemStack.isSameItemSameComponents(there, stack) || !container.canPlaceItem(i, stack)) continue;
            int room = Math.min(container.getMaxStackSize(there), there.getMaxStackSize()) - there.getCount();
            if (room <= 0) continue;
            int move = Math.min(room, stack.getCount());
            ItemStack grown = there.copy();
            grown.grow(move);
            container.setItem(i, grown);
            stack.shrink(move);
        }
        for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
            if (!container.getItem(i).isEmpty() || !container.canPlaceItem(i, stack)) continue;
            int move = Math.min(stack.getCount(), Math.min(container.getMaxStackSize(stack), stack.getMaxStackSize()));
            container.setItem(i, stack.split(move));
        }
    }

    /** A crate taken away other than by its owner emptying it: what is in it drops, and its marker goes. */
    static void removed(ServerLevel level, DeathCrateBlockEntity crate) {
        BlockPos pos = crate.getBlockPos();
        for (DeathCrateBlockEntity.Entry e : crate.entries) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, e.stack());
        crate.entries.clear();
        MapServer.removeMarker(level.getServer(), crate.markerId);
        crate.markerId = "";
    }
}
