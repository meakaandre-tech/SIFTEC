package com.meakaandre.siftec.compat;

import com.meakaandre.siftec.SelfTestHooks;
import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.backpack.Backpack;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.death.DeathCrates;
import com.meakaandre.siftec.hub.Locks;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.owner.RecipeLocks;
import com.tiviacz.travelersbackpack.attachment.AttachmentUtils;
import com.tiviacz.travelersbackpack.attachment.BackpackAttachment;
import com.tiviacz.travelersbackpack.component.RenderInfo;
import com.tiviacz.travelersbackpack.init.ModDataComponents;
import com.tiviacz.travelersbackpack.init.ModItems;
import com.tiviacz.travelersbackpack.inventory.BackpackWrapper;
import com.tiviacz.travelersbackpack.inventory.Tiers;
import com.tiviacz.travelersbackpack.inventory.handler.ItemStackHandler;
import com.tiviacz.travelersbackpack.inventory.menu.BackpackBaseMenu;
import com.tiviacz.travelersbackpack.inventory.upgrades.crafting.CraftingUpgrade;
import com.tiviacz.travelersbackpack.item.TravelersBackpackItem;
import com.tiviacz.travelersbackpack.util.ContainerContentsHelper;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Traveler's Backpack as every player's inventory (Exploration and Equipment tab, Backpack). Loaded only when that mod
 * is installed.
 * <ul>
 * <li>Always worn: every player wears one from spawn, Leather. Nobody can craft one (every backpack, design, tier
 * upgrade and sleeping bag is switched off, see tools/content.py).</li>
 * <li>Grows with the company: its tier is the highest "backpack:&lt;tier&gt;" token among the company's finished
 * milestones (Iron at Field Research, Gold at Vehicular Transport, Diamond at Jetpack, Netherite at Hover Pack, the
 * milestones where Satisfactory gives inventory slots). Members who were offline get theirs when they next join.</li>
 * <li>Bound: it never leaves the player's back. Unequipping, swapping and placing it are refused (mixins in
 * {@code mixin/tb}); on death it stays on, with its upgrades, while everything in it goes into the death crate
 * ({@link DeathCrates}); a backpack item that turns up in an inventory is unpacked.</li>
 * <li>Crafting: one upgrade slot holds a built-in 2x2 Crafting Grid (a Crafting Upgrade marked as the grid, see
 * {@link #isGrid}), which cannot be taken out. Using a Crafting Upgrade turns it into the full 3x3 grid.</li>
 * <li>Its upgrades are locked by MAM research like everything else, so a locked one cannot go into an upgrade slot,
 * and they are built at the Equipment Workshop (or by the Assembler).</li>
 * </ul>
 * E opens it in place of the vanilla inventory and its window shows the armour and offhand slots ({@link TbClient},
 * {@link TbEquipSlots}). Everything is checked once a second for every player online, and when a player joins or
 * respawns.
 */
public final class TravelersBackpackCompat {
    /** The tokens that raise the backpack, by tier ordinal (Leather, tier 0, is everyone's from the start). */
    public static final List<String> TIER_TOKENS = List.of("", "backpack:iron", "backpack:gold", "backpack:diamond", "backpack:netherite");
    /** The mark on the Crafting Upgrade that is the backpack's built-in 2x2 grid. */
    public static final String GRID_KEY = "siftec_grid";
    /** Storage slots past which Traveler's Backpack draws the extended layout (11 a row, 2 more tank rows). */
    private static final int EXTENDED_SLOTS = 81;
    /** Storage slots a row in the extended and the normal layout. */
    private static final int EXTENDED_ROW = 11, NORMAL_ROW = 9;
    /** Tank rows the extended layout adds. */
    private static final int EXTENDED_EXTRA_ROWS = 2;
    /** Slots read out of a backpack container when trimming it, more than any tier holds. */
    private static final int TRIM_SLOTS = 512;
    /** Side of the built-in crafting grid, and of a Crafting Upgrade's. */
    private static final int BUILT_IN_GRID = 2, UPGRADE_GRID = 3;
    /** Worn backpacks are brought in line with the company's tier this often (ticks), at this offset in the second. */
    private static final int SYNC_TICKS = 20, SYNC_PHASE = 7;
    /** While true the once-a-second check skips players, as if they were offline (selftests). */
    static volatile boolean held;

    private TravelersBackpackCompat() {
    }

    public static void register() {
        Backpack.worn = TravelersBackpackCompat::containers;
        DeathCrates.backpack = new DeathCrates.BackpackAccess() {
            @Override
            public List<Container> containers(ServerPlayer player) {
                return TravelersBackpackCompat.crateContainers(player);
            }

            @Override
            public boolean spillInto(int index) {
                // storage and tools take anything that fits; the crafting grid only its own stacks back
                return index < 2;
            }

            @Override
            public void afterRestore(ServerPlayer player) {
                sync(player);
            }
        };
        ServerPlayerEvents.JOIN.register(TravelersBackpackCompat::sync);
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> sync(newPlayer));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % SYNC_TICKS != SYNC_PHASE || held) return;
            for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) sync(player);
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (!isCraftingUpgrade(stack) || isGrid(stack)) return InteractionResult.PASS;
            if (player instanceof ServerPlayer server) installCraftingUpgrade(server, stack);
            return InteractionResult.SUCCESS;
        });
        SelfTestHooks.run(SelfTestHooks.Stage.BACKPACK);
        Siftec.LOGGER.info("SIFTEC: Traveler's Backpack found; it is every player's inventory, raised by milestones and bound to them");
    }

    // ------------------------------------------------------------------------------------------------ tiers

    /** The tier the company's backpacks have, 0 Leather to 4 Netherite. */
    public static int tierFor(Company company) {
        if (company == null) return 0;
        int tier = 0;
        for (String id : company.done) {
            Milestone m = Milestones.get(id);
            if (m == null) continue;
            for (int t = 1; t < TIER_TOKENS.size(); t++) {
                if (m.tokens().contains(TIER_TOKENS.get(t))) tier = Math.max(tier, t);
            }
        }
        return tier;
    }

    public static int tierFor(ServerPlayer player) {
        return tierFor(Companies.find(player));
    }

    /** The milestone that raises the backpack to this tier, or null (Leather, or a tier nothing raises it to). */
    public static Milestone milestoneFor(int tier) {
        if (tier <= 0 || tier >= TIER_TOKENS.size()) return null;
        for (Milestone m : Milestones.all()) if (m.tokens().contains(TIER_TOKENS.get(tier))) return m;
        return null;
    }

    // ------------------------------------------------------------------------------------------------ the worn backpack

    public static boolean wearing(Player player) {
        return AttachmentUtils.getAttachment(player).map(BackpackAttachment::hasBackpack).orElse(false);
    }

    public static ItemStack worn(Player player) {
        return AttachmentUtils.getAttachment(player).map(BackpackAttachment::getBackpack).orElse(ItemStack.EMPTY);
    }

    /** The tier of the backpack the player wears, or -1. */
    public static int wornTier(Player player) {
        ItemStack stack = worn(player);
        return isBackpack(stack) ? stack.getOrDefault(ModDataComponents.TIER, 0) : -1;
    }

    /** The worn backpack's live wrapper, made first if the mod has not yet, with every part of it loaded; or null. */
    public static BackpackWrapper wrapper(Player player) {
        BackpackAttachment attachment = AttachmentUtils.getAttachment(player).orElse(null);
        if (attachment == null || !attachment.hasBackpack()) return null;
        if (attachment.getWrapper() == null) attachment.equipBackpack(attachment.getBackpack(), player);
        BackpackWrapper wrapper = attachment.getWrapper();
        if (wrapper == null) return null;
        wrapper.loadAdditionally(BackpackWrapper.STORAGE_ID);
        wrapper.loadAdditionally(BackpackWrapper.UPGRADES_ID);
        wrapper.loadAdditionally(BackpackWrapper.TOOLS_ID);
        return wrapper;
    }

    /** The worn backpack's live storage (null when none). */
    public static ItemStackHandler storage(Player player) {
        BackpackWrapper wrapper = wrapper(player);
        return wrapper == null ? null : wrapper.getStorage();
    }

    private static List<Container> containers(ServerPlayer player) {
        ItemStackHandler storage = storage(player);
        return storage == null ? List.of() : List.of(storage);
    }

    /**
     * What the death crate takes out of the backpack and puts back: its storage, its tool slots, then what lies on its
     * crafting grid. Not the upgrades. The order is fixed (a crate's entries name the container by its place in it), so
     * a missing part is an empty stand-in.
     */
    private static List<Container> crateContainers(ServerPlayer player) {
        BackpackWrapper wrapper = wrapper(player);
        if (wrapper == null) return List.of();
        List<Container> out = new ArrayList<>();
        out.add(wrapper.getStorage() != null ? wrapper.getStorage() : new SimpleContainer(0));
        out.add(wrapper.getTools() != null ? wrapper.getTools() : new SimpleContainer(0));
        Container grid = grid(player);
        out.add(grid != null ? grid : new SimpleContainer(0));
        return out;
    }

    /** What lies on the worn backpack's crafting grid (the built-in 2x2 or a Crafting Upgrade's 3x3), or null. */
    public static @Nullable Container grid(Player player) {
        BackpackWrapper wrapper = wrapper(player);
        if (wrapper == null || wrapper.getUpgradeManager() == null) return null;
        return wrapper.getUpgradeManager().getUpgrade(CraftingUpgrade.class)
            .map(upgrade -> (Container) upgrade.crafting).orElse(null);
    }

    /** Puts what fits of the stack into the worn backpack's storage; returns what is left. */
    public static ItemStack insert(Player player, ItemStack stack) {
        ItemStackHandler storage = storage(player);
        if (storage == null) return stack;
        for (int i = 0; i < storage.getSlots() && !stack.isEmpty(); i++) {
            if (!storage.getStackInSlot(i).isEmpty()) stack = storage.insertItem(i, stack, false);
        }
        for (int i = 0; i < storage.getSlots() && !stack.isEmpty(); i++) stack = storage.insertItem(i, stack, false);
        return stack;
    }

    /** Sets a backpack's tier and the sizes that go with it, as the mod's own tier upgrade does. */
    public static void applyTier(ItemStack stack, int ordinal) {
        Tiers.Tier tier = Tiers.of(ordinal);
        stack.set(ModDataComponents.TIER, tier.getOrdinal());
        stack.set(ModDataComponents.STORAGE_SLOTS, tier.getStorageSlots());
        stack.set(ModDataComponents.UPGRADE_SLOTS, tier.getUpgradeSlots());
        stack.set(ModDataComponents.TOOL_SLOTS, tier.getToolSlots());
        if (stack.has(ModDataComponents.RENDER_INFO)) {
            int slots = tier.getStorageSlots();
            boolean extended = slots > EXTENDED_SLOTS;
            int rows = (int) Math.ceil((double) slots / (extended ? EXTENDED_ROW : NORMAL_ROW)) + (extended ? EXTENDED_EXTRA_ROWS : 0);
            CompoundTag tag = stack.get(ModDataComponents.RENDER_INFO).compoundTag().copy();
            RenderInfo info = new RenderInfo(tag);
            info.updateCapacity(tier.getTankCapacityPerRow() * rows);
            stack.set(ModDataComponents.RENDER_INFO, info);
        }
    }

    /** A new backpack of the given tier, with the built-in crafting grid in its first upgrade slot. */
    public static ItemStack newBackpack(int ordinal) {
        ItemStack stack = new ItemStack(ModItems.STANDARD_TRAVELERS_BACKPACK);
        applyTier(stack, ordinal);
        NonNullList<ItemStack> upgrades = NonNullList.withSize(Tiers.of(ordinal).getUpgradeSlots(), ItemStack.EMPTY);
        if (!upgrades.isEmpty()) upgrades.set(0, newGrid());
        stack.set(ModDataComponents.UPGRADES, ItemContainerContents.fromItems(upgrades));
        return stack;
    }

    /** Puts the backpack on the player's back, closing the backpack window first if it is open. */
    private static void equip(ServerPlayer player, BackpackAttachment attachment, ItemStack stack) {
        if (player.containerMenu != player.inventoryMenu && player.containerMenu.getClass().getName().startsWith("com.tiviacz.travelersbackpack")) {
            player.closeContainer();
        }
        attachment.equipBackpack(stack, player);
        attachment.synchronise(player);
    }

    /** True for an item that is a Traveler's Backpack. */
    public static boolean isBackpack(ItemStack stack) {
        return stack.getItem() instanceof TravelersBackpackItem;
    }

    // ------------------------------------------------------------------------------------------------ the crafting grid

    public static boolean isCraftingUpgrade(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItems.CRAFTING_UPGRADE);
    }

    /** True for the Crafting Upgrade that is the backpack's built-in 2x2 grid. */
    public static boolean isGrid(ItemStack stack) {
        if (!isCraftingUpgrade(stack)) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBooleanOr(GRID_KEY, false);
    }

    /** The built-in 2x2 grid: a Crafting Upgrade with SIFTEC's mark, named for what it is. */
    public static ItemStack newGrid() {
        ItemStack grid = new ItemStack(ModItems.CRAFTING_UPGRADE);
        CustomData.update(DataComponents.CUSTOM_DATA, grid, tag -> tag.putBoolean(GRID_KEY, true));
        grid.set(DataComponents.ITEM_NAME, Component.translatable("siftec.backpack.grid"));
        return grid;
    }

    /** The worn backpack's upgrade slots as they are saved on it. */
    private static NonNullList<ItemStack> upgrades(ItemStack backpack) {
        int size = backpack.getOrDefault(ModDataComponents.UPGRADE_SLOTS, Tiers.of(backpack.getOrDefault(ModDataComponents.TIER, 0)).getUpgradeSlots());
        return ContainerContentsHelper.getItems(backpack.getOrDefault(ModDataComponents.UPGRADES, ItemContainerContents.EMPTY), size);
    }

    /** The slot of the worn backpack's crafting grid (the built-in one or a real Crafting Upgrade), or -1. */
    public static int gridSlot(Player player) {
        NonNullList<ItemStack> ups = upgrades(worn(player));
        for (int i = 0; i < ups.size(); i++) if (isCraftingUpgrade(ups.get(i))) return i;
        return -1;
    }

    /** 2 for the built-in grid, 3 once a Crafting Upgrade is in, 0 when there is no grid. */
    public static int gridSize(Player player) {
        int slot = gridSlot(player);
        if (slot < 0) return 0;
        return isGrid(upgrades(worn(player)).get(slot)) ? BUILT_IN_GRID : UPGRADE_GRID;
    }

    /** Gives the worn backpack its built-in grid if it has no crafting grid and a free upgrade slot. */
    private static void ensureGrid(ServerPlayer player, BackpackAttachment attachment) {
        ItemStack backpack = attachment.getBackpack();
        NonNullList<ItemStack> ups = upgrades(backpack);
        int free = -1;
        for (int i = 0; i < ups.size(); i++) {
            if (isCraftingUpgrade(ups.get(i))) return;
            if (free < 0 && ups.get(i).isEmpty()) free = i;
        }
        if (free < 0) return;
        ItemStack copy = backpack.copy();
        ups.set(free, newGrid());
        copy.set(ModDataComponents.UPGRADES, ItemContainerContents.fromItems(ups));
        equip(player, attachment, copy);
    }

    /**
     * A Crafting Upgrade used by its holder: the backpack's built-in 2x2 grid becomes the full 3x3 one (keeping what is
     * on it) and the upgrade is used up. Returns whether it did.
     */
    public static boolean installCraftingUpgrade(ServerPlayer player, ItemStack held) {
        BackpackAttachment attachment = AttachmentUtils.getAttachment(player).orElse(null);
        if (attachment == null || !attachment.hasBackpack()) return false;
        if (!Locks.allowed(player, held)) {
            Milestone lock = Locks.lockOf(held.getItem());
            if (lock != null) Locks.tellLocked(player, lock);
            return false;
        }
        ItemStack backpack = attachment.getBackpack();
        NonNullList<ItemStack> ups = upgrades(backpack);
        int slot = -1;
        for (int i = 0; i < ups.size(); i++) if (isCraftingUpgrade(ups.get(i))) slot = i;
        if (slot >= 0 && !isGrid(ups.get(slot))) {
            player.sendOverlayMessage(Component.translatable("siftec.backpack.grid.already"));
            return false;
        }
        if (slot < 0) {
            for (int i = 0; i < ups.size() && slot < 0; i++) if (ups.get(i).isEmpty()) slot = i;
            if (slot < 0) return false;
            ups.set(slot, new ItemStack(ModItems.CRAFTING_UPGRADE));
        } else {
            ItemStack full = ups.get(slot).copy();
            CustomData.update(DataComponents.CUSTOM_DATA, full, tag -> tag.remove(GRID_KEY));
            if (full.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).isEmpty()) full.remove(DataComponents.CUSTOM_DATA);
            full.remove(DataComponents.ITEM_NAME);
            ups.set(slot, full);
        }
        ItemStack copy = backpack.copy();
        copy.set(ModDataComponents.UPGRADES, ItemContainerContents.fromItems(ups));
        equip(player, attachment, copy);
        if (!player.hasInfiniteMaterials()) held.shrink(1);
        player.sendSystemMessage(Component.translatable("siftec.backpack.grid.installed").withStyle(ChatFormatting.GOLD));
        Siftec.LOGGER.info("SIFTEC: {} turned their backpack's crafting grid into 3x3", player.getGameProfile().name());
        return true;
    }

    // ------------------------------------------------------------------------------------------------ the rules

    /**
     * Brings the player in line with the rules: a backpack item found in the inventory or on the cursor is unpacked, a
     * player without a backpack is given one, a backpack below the company's tier is raised (one above it, from a company
     * left behind, is lowered and its extra slots go into a crate), and a backpack without a
     * crafting grid gets its built-in one.
     */
    public static void sync(ServerPlayer player) {
        if (player.isRemoved() || Companies.isFake(player)) return;
        BackpackAttachment attachment = AttachmentUtils.getAttachment(player).orElse(null);
        if (attachment == null) return;
        int want = tierFor(player);
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!isBackpack(stack)) continue;
            inventory.setItem(i, ItemStack.EMPTY);
            stray(player, attachment, stack, want);
        }
        ItemStack carried = player.containerMenu.getCarried();
        if (isBackpack(carried)) {
            player.containerMenu.setCarried(ItemStack.EMPTY);
            stray(player, attachment, carried, want);
        }
        if (!attachment.hasBackpack()) {
            equip(player, attachment, newBackpack(want));
            Siftec.LOGGER.info("SIFTEC: gave {} their backpack, tier {}", player.getGameProfile().name(), want);
        }
        if (attachment.getWrapper() == null) attachment.equipBackpack(attachment.getBackpack(), player);
        int tier = wornTier(player);
        if (tier >= 0 && want > tier) {
            ItemStack raised = attachment.getBackpack().copy();
            applyTier(raised, want);
            equip(player, attachment, raised);
            player.sendSystemMessage(Component.translatable("siftec.backpack.tier", Tiers.of(want).getLocalizedName(), Tiers.of(want).getStorageSlots())
                .withStyle(ChatFormatting.GOLD));
            Siftec.LOGGER.info("SIFTEC: raised {}'s backpack from tier {} to {}", player.getGameProfile().name(), tier, want);
        } else if (tier > want) {
            lower(player, attachment, tier, want);
        }
        ensureGrid(player, attachment);
    }

    /**
     * The player's company now has a lower tier than their backpack (they left a company that had more): the backpack
     * goes down to the company's tier, and what lay in the storage, tool and upgrade slots it no longer has goes into a
     * crate of theirs (death.DeathCrates#stashFor). The crafting grid stays.
     */
    private static void lower(ServerPlayer player, BackpackAttachment attachment, int tier, int want) {
        ItemStack lowered = attachment.getBackpack().copy();
        Tiers.Tier to = Tiers.of(want);
        List<ItemStack> extra = new ArrayList<>();
        extra.addAll(trim(lowered, ModDataComponents.BACKPACK_CONTAINER, to.getStorageSlots()));
        extra.addAll(trim(lowered, ModDataComponents.TOOLS_CONTAINER, to.getToolSlots()));
        extra.addAll(trim(lowered, ModDataComponents.UPGRADES, to.getUpgradeSlots()));
        extra.removeIf(TravelersBackpackCompat::isGrid);
        applyTier(lowered, want);
        equip(player, attachment, lowered);
        if (!extra.isEmpty()) DeathCrates.stashFor(player, extra, "siftec.backpack.lowered_crate");
        player.sendSystemMessage(Component.translatable("siftec.backpack.lowered", to.getLocalizedName(), to.getStorageSlots()).withStyle(ChatFormatting.YELLOW));
        Siftec.LOGGER.info("SIFTEC: lowered {}'s backpack from tier {} to {}; {} stacks went into a crate", player.getGameProfile().name(), tier, want, extra.size());
    }

    /** Keeps the first {@code keep} slots of one of the backpack's containers and returns what lay in the rest. */
    static List<ItemStack> trim(ItemStack backpack, DataComponentType<ItemContainerContents> type, int keep) {
        List<ItemStack> extra = new ArrayList<>();
        ItemContainerContents contents = backpack.get(type);
        if (contents == null) return extra;
        NonNullList<ItemStack> all = NonNullList.withSize(TRIM_SLOTS, ItemStack.EMPTY);
        contents.copyInto(all);
        int last = 0;
        for (int i = 0; i < all.size(); i++) if (!all.get(i).isEmpty()) last = i + 1;
        if (last <= keep) return extra;
        NonNullList<ItemStack> kept = NonNullList.withSize(Math.max(0, keep), ItemStack.EMPTY);
        for (int i = 0; i < all.size(); i++) {
            if (i < keep) kept.set(i, all.get(i));
            else if (!all.get(i).isEmpty()) extra.add(all.get(i));
        }
        backpack.set(type, ItemContainerContents.fromItems(kept));
        return extra;
    }

    /** A backpack item found off the player's back: worn if they somehow have none on, else emptied into the inventory. */
    private static void stray(ServerPlayer player, BackpackAttachment attachment, ItemStack stack, int want) {
        if (!attachment.hasBackpack()) {
            if (stack.getOrDefault(ModDataComponents.TIER, 0) < want) applyTier(stack, want);
            equip(player, attachment, stack);
            return;
        }
        unpack(player, stack);
        player.sendSystemMessage(Component.translatable("siftec.backpack.extra").withStyle(ChatFormatting.RED));
    }

    /** Gives the player everything an extra backpack holds (storage, upgrades, tools); the backpack itself is gone. */
    public static int unpack(ServerPlayer player, ItemStack backpack) {
        int n = 0;
        for (DataComponentType<ItemContainerContents> type : List.of(ModDataComponents.BACKPACK_CONTAINER, ModDataComponents.UPGRADES, ModDataComponents.TOOLS_CONTAINER)) {
            ItemContainerContents contents = backpack.get(type);
            if (contents == null) continue;
            for (ItemStack stack : contents.nonEmptyItemCopyStream().toList()) {
                if (isGrid(stack)) continue;
                player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
                n++;
            }
        }
        Siftec.LOGGER.info("SIFTEC: unpacked an extra Traveler's Backpack of {} ({} stacks)", player.getGameProfile().name(), n);
        return n;
    }

    /**
     * May the player take what the backpack's crafting grid makes, by click, shift-click (shift-craft), number key or
     * drop: not a locked item ({@link Locks#mayTake}, which tells them why), nor on the server the result of a recipe
     * their company has not unlocked ({@link RecipeLocks}). The grid's result slot
     * (ResultSlotExt) answers mayPickup itself, so the vanilla check in mixin/SlotMixin never sees it.
     */
    public static boolean mayTakeResult(Player player, BackpackBaseMenu menu, ItemStack result) {
        if (!Locks.mayTake(player, result)) return false;
        if (!(player instanceof ServerPlayer server) || result.isEmpty() || menu.getWrapper() == null || menu.getWrapper().getUpgradeManager() == null) return true;
        var upgrade = menu.getWrapper().getUpgradeManager().getUpgrade(CraftingUpgrade.class).orElse(null);
        var recipe = upgrade == null ? null : upgrade.resultSlots.getRecipeUsed();
        if (recipe == null || !RecipeLocks.blocked(server, recipe.value())) return true;
        Milestone lock = Milestones.get(RecipeLocks.lockOf(server.level().getServer(), recipe.value()));
        if (lock != null) Locks.tellLocked(server, lock);
        return false;
    }

    /** May this player put this upgrade into their backpack: only once their company has unlocked it. */
    public static boolean mayUseUpgrade(Player player, ItemStack stack) {
        return Locks.allowed(player, stack);
    }

    /** May the upgrade in this slot of the player's worn backpack be taken out: never the built-in grid. */
    public static boolean mayRemoveUpgrade(Player player, int slot) {
        NonNullList<ItemStack> ups = upgrades(worn(player));
        if (slot < 0 || slot >= ups.size() || !isGrid(ups.get(slot))) return true;
        if (player instanceof ServerPlayer server) server.sendOverlayMessage(Component.translatable("siftec.backpack.grid.locked"));
        return false;
    }

    /** The message when the mod would take the backpack off the player's back. */
    public static void refuseUnequip(Player player) {
        if (player instanceof ServerPlayer server) {
            server.sendOverlayMessage(Component.translatable("siftec.backpack.bound").withStyle(ChatFormatting.GOLD));
        }
    }
}
