package com.meakaandre.siftec.compat;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildGunItem;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.death.DeathCrateBlockEntity;
import com.meakaandre.siftec.death.DeathCrates;
import com.meakaandre.siftec.hub.Locks;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.map.MapMarker;
import com.meakaandre.siftec.map.MapMarkers;
import com.meakaandre.siftec.registry.ModBlocks;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.tiviacz.travelersbackpack.attachment.AttachmentUtils;
import com.tiviacz.travelersbackpack.attachment.BackpackAttachment;
import com.tiviacz.travelersbackpack.common.ServerActions;
import com.tiviacz.travelersbackpack.init.ModDataComponents;
import com.tiviacz.travelersbackpack.init.ModItems;
import com.tiviacz.travelersbackpack.inventory.BackpackContainer;
import com.tiviacz.travelersbackpack.inventory.BackpackWrapper;
import com.tiviacz.travelersbackpack.inventory.handler.ItemStackHandler;
import com.tiviacz.travelersbackpack.inventory.menu.BackpackBaseMenu;
import com.tiviacz.travelersbackpack.inventory.menu.BackpackItemMenu;
import com.tiviacz.travelersbackpack.inventory.menu.slot.ResultSlotExt;
import com.tiviacz.travelersbackpack.inventory.menu.slot.UpgradeLockableSlotItemHandler;
import com.tiviacz.travelersbackpack.inventory.upgrades.crafting.CraftingUpgrade;
import com.tiviacz.travelersbackpack.util.ContainerContentsHelper;
import com.tiviacz.travelersbackpack.util.Reference;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Traveler's Backpack checks ({@code /siftec selftest tb ...}), on every player online; the smoke run's client drives
 * them between its screenshots. Every check prints "SELFTEST tb PASS ..." or "SELFTEST tb FAIL ...".
 * <ul>
 * <li>{@code setup}: back to a new player of a new company: worn from the start, Leather, the built-in 2x2 grid;</li>
 * <li>{@code screen}: what E opened is the backpack window, with the armour and offhand slots;</li>
 * <li>{@code tier <n>}: the company finishes the milestones up to backpack tier n (0 Leather to 4 Netherite);</li>
 * <li>{@code rules}: no backpack recipe is left, the upgrades come from the Workshop, unequipping and swapping are
 * refused, the grid stays in, an extra backpack is unpacked, a locked upgrade does not fit, payments see it;</li>
 * <li>{@code craft grid} / {@code craft full}: the 2x2 grid makes a crafting table and has no room for a chest; a
 * Crafting Upgrade makes it 3x3, which makes the chest (each leaves its window open for a screenshot);</li>
 * <li>{@code crate setup} / {@code crate check}: things in known slots before dying; after respawning, a Death Crate
 * marked on the map holds them and one click puts every one back in its slot;</li>
 * <li>{@code crate keep} / {@code crate kept} / {@code crate persisted}: a crate left alone stays, through a restart;</li>
 * <li>{@code fixes}: the checks that need a real player, logged by number: 3, a shift-click leaving part of a stack
 * in a backpack slot saves what is left; 4, a Build Gun in the backpack stays out of the crate and the crafting grid's
 * stacks go into it and back.</li>
 * <li>{@code fixes2}: the crafting grid keeps to the locks: a locked result cannot be taken out by a click nor made by
 * shift-crafting, and can once its milestone is done. (The backpack window's Cloud button is the client's to check: it
 * logs "SELFTEST tb-client" the first time the window opens, see client/CloudButton.java; the server side of what the
 * backpack pays for is {@code /siftec selftest backpackfix}.)</li>
 * </ul>
 */
public final class TravelersBackpackSelfTest {
    /** Where crate setup put things: (where, slot) -> stack, as DeathCrateBlockEntity.Entry counts them. */
    private static final Map<Long, ItemStack> PUT = new LinkedHashMap<>();
    private static final String CRAFTING_NODE = "mam_megafauna_structural_analysis";
    private static final String PICKUP_NODE = "mam_caterium_quickwire";

    private TravelersBackpackSelfTest() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("tb")
                    .then(Commands.literal("setup").executes(context -> setup(context.getSource())))
                    .then(Commands.literal("screen").executes(context -> screen(context.getSource())))
                    .then(Commands.literal("tier").then(Commands.argument("n", IntegerArgumentType.integer(0, 4))
                        .executes(context -> tier(context.getSource(), IntegerArgumentType.getInteger(context, "n")))))
                    .then(Commands.literal("rules").executes(context -> rules(context.getSource())))
                    .then(Commands.literal("craft")
                        .then(Commands.literal("grid").executes(context -> craftGrid(context.getSource())))
                        .then(Commands.literal("full").executes(context -> craftFull(context.getSource()))))
                    .then(Commands.literal("crate")
                        .then(Commands.literal("setup").executes(context -> crateSetup(context.getSource())))
                        .then(Commands.literal("check").executes(context -> crateCheck(context.getSource())))
                        .then(Commands.literal("keep").executes(context -> crateKeep(context.getSource())))
                        .then(Commands.literal("kept").executes(context -> crateKept(context.getSource(), false)))
                        .then(Commands.literal("persisted").executes(context -> crateKept(context.getSource(), true))))
                    .then(Commands.literal("fixes").executes(context -> fixes(context.getSource())))
                    .then(Commands.literal("fixes2").executes(context -> fixes2(context.getSource())))))));
    }

    private static List<ServerPlayer> players(CommandSourceStack source) {
        return List.copyOf(source.getServer().getPlayerList().getPlayers());
    }

    private static void report(CommandSourceStack source, String text) {
        String line = "SELFTEST tb " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        report(source, (ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    /** The company has done exactly the backpack milestones up to tier n. */
    private static void setTier(MinecraftServer server, Company company, int n) {
        for (int t = 1; t < TravelersBackpackCompat.TIER_TOKENS.size(); t++) {
            Milestone m = TravelersBackpackCompat.milestoneFor(t);
            if (m == null) continue;
            if (t <= n) company.done.add(m.id());
            else company.done.remove(m.id());
        }
        Companies.save(server);
    }

    private static String state(ServerPlayer player) {
        ItemStack worn = TravelersBackpackCompat.worn(player);
        ItemStackHandler storage = TravelersBackpackCompat.storage(player);
        return "wearing " + TravelersBackpackCompat.wearing(player) + ", tier " + TravelersBackpackCompat.wornTier(player)
            + ", storage " + (storage == null ? 0 : storage.getSlots()) + ", upgrade slots " + worn.getOrDefault(ModDataComponents.UPGRADE_SLOTS, 0)
            + ", grid " + TravelersBackpackCompat.gridSize(player) + "x" + TravelersBackpackCompat.gridSize(player);
    }

    private static void clearAll(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) inventory.setItem(i, ItemStack.EMPTY);
        BackpackWrapper wrapper = TravelersBackpackCompat.wrapper(player);
        if (wrapper == null) return;
        for (ItemStackHandler part : List.of(wrapper.getStorage(), wrapper.getTools())) {
            if (part != null) for (int i = 0; i < part.getSlots(); i++) part.setStackInSlot(i, ItemStack.EMPTY);
        }
        // what an earlier crafting check left on the grid (the death crate takes that too)
        Container grid = TravelersBackpackCompat.grid(player);
        if (grid != null) for (int i = 0; i < grid.getContainerSize(); i++) grid.setItem(i, ItemStack.EMPTY);
    }

    // ------------------------------------------------------------------------------------------------ worn and tiers

    private static int setup(CommandSourceStack source) {
        for (ServerPlayer player : players(source)) {
            Company company = Companies.of(player);
            setTier(source.getServer(), company, 0);
            company.done.remove(CRAFTING_NODE);
            company.done.remove(PICKUP_NODE);
            Companies.save(source.getServer());
            player.closeContainer();
            AttachmentUtils.getAttachment(player).ifPresent(a -> {
                a.remove(player);
                a.synchronise(player);
            });
            clearAll(player);
            TravelersBackpackCompat.sync(player);
            ItemStackHandler storage = TravelersBackpackCompat.storage(player);
            expect(source, "a new player wears their backpack from the start: Leather, 27 slots, the built-in 2x2 grid",
                TravelersBackpackCompat.wearing(player) && TravelersBackpackCompat.wornTier(player) == 0 && storage != null && storage.getSlots() == 27
                    && TravelersBackpackCompat.gridSize(player) == 2, state(player));
        }
        return 1;
    }

    private static int screen(CommandSourceStack source) {
        for (ServerPlayer player : players(source)) {
            boolean backpack = player.containerMenu instanceof BackpackItemMenu menu && menu.getWrapper().getScreenID() == Reference.WEARABLE_SCREEN_ID;
            expect(source, "E opened the backpack window in place of the vanilla inventory", backpack, player.containerMenu.getClass().getSimpleName());
            int equipment = 0, hotbar = 0;
            for (Slot slot : player.containerMenu.slots) {
                if (TbEquipSlots.isEquipSlot(slot)) equipment++;
                if (slot.container instanceof Inventory && slot.getContainerSlot() < Inventory.getSelectionSize()) hotbar++;
            }
            expect(source, "the window shows the armour and offhand slots, and the vanilla hotbar", equipment == 5 && hotbar == 9,
                equipment + " equipment slots, " + hotbar + " hotbar slots");
        }
        return 1;
    }

    private static int tier(CommandSourceStack source, int n) {
        for (ServerPlayer player : players(source)) {
            Company company = Companies.of(player);
            int before = TravelersBackpackCompat.wornTier(player);
            setTier(source.getServer(), company, n);
            Milestone m = TravelersBackpackCompat.milestoneFor(n);
            TravelersBackpackCompat.sync(player);
            int want = Math.max(before, n);
            ItemStackHandler storage = TravelersBackpackCompat.storage(player);
            int[] slots = {27, 45, 63, 81, 99};
            expect(source, "milestone " + (m == null ? "-" : m.id()) + " raises the backpack to tier " + n + " (never lowered)",
                TravelersBackpackCompat.wornTier(player) == want && storage != null && storage.getSlots() == slots[want] && TravelersBackpackCompat.gridSize(player) > 0,
                state(player));
        }
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ the rules

    private static int rules(CommandSourceStack source) {
        int left = 0;
        List<String> kept = new ArrayList<>();
        for (var holder : source.getServer().getRecipeManager().getRecipes()) {
            Identifier rid = holder.id().identifier();
            if (rid.getNamespace().equals("travelersbackpack")) {
                left++;
                kept.add(rid.getPath());
            }
        }
        // every recipe of the mod makes a backpack, a design, a tier upgrade or a sleeping bag (all switched off), or
        // one of the upgrades, which the pack removes: none of the upgrades' may be left
        long upgradesLeft = kept.stream().filter(p -> Milestones.removedRecipes().contains(Identifier.fromNamespaceAndPath("travelersbackpack", p))).count();
        expect(source, "the upgrades' own crafting recipes are gone", upgradesLeft == 0, left + " Traveler's Backpack recipes loaded");
        boolean disabled = Locks.lockOf(ModItems.STANDARD_TRAVELERS_BACKPACK) == Locks.DISABLED && Locks.lockOf(ModItems.BLANK_UPGRADE) == Locks.DISABLED;
        expect(source, "the backpack and the Blank Upgrade are switched off", disabled, "");
        int workshop = 0;
        for (Milestones.Build build : Milestones.workshop()) if (build.item().getNamespace().equals("travelersbackpack")) workshop++;
        Milestone craftingLock = Locks.lockOf(ModItems.CRAFTING_UPGRADE);
        expect(source, "the Equipment Workshop builds the upgrades, each locked by MAM research", workshop == 15 && craftingLock != null && craftingLock.id().equals(CRAFTING_NODE),
            workshop + " builds; Crafting Upgrade needs " + (craftingLock == null ? "nothing" : craftingLock.id()));
        for (ServerPlayer player : players(source)) {
            TravelersBackpackCompat.sync(player);
            BackpackAttachment attachment = AttachmentUtils.getAttachment(player).orElseThrow();
            ItemStackHandler storage = TravelersBackpackCompat.storage(player);
            if (storage == null) {
                expect(source, "a backpack is worn", false, state(player));
                continue;
            }
            storage.setStackInSlot(0, new ItemStack(Items.GOLD_INGOT, 5));
            boolean unequip = ServerActions.unequipBackpack(player);
            player.setItemInHand(InteractionHand.MAIN_HAND, TravelersBackpackCompat.newBackpack(0));
            boolean swap = ServerActions.swapBackpack(player);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            expect(source, "the backpack cannot be taken off or swapped", !unequip && !swap && TravelersBackpackCompat.wearing(player)
                && TravelersBackpackCompat.storage(player).getStackInSlot(0).is(Items.GOLD_INGOT), "unequip " + unequip + ", swap " + swap);
            // the built-in grid stays in its slot
            BackpackContainer.openBackpack(player, attachment.getBackpack(), Reference.WEARABLE_SCREEN_ID);
            int gridSlot = TravelersBackpackCompat.gridSlot(player);
            ServerActions.removeBackpackUpgrade(player, gridSlot);
            boolean gridStays = TravelersBackpackCompat.gridSize(player) == 2 && player.getInventory().countItem(ModItems.CRAFTING_UPGRADE) == 0;
            // a locked upgrade does not fit an upgrade slot; once researched it does
            Company company = Companies.of(player);
            ItemStack pickup = new ItemStack(ModItems.PICKUP_UPGRADE);
            company.done.remove(PICKUP_NODE);
            Boolean before = freeUpgradeSlotTakes(player, pickup);
            company.done.add(PICKUP_NODE);
            Boolean after = freeUpgradeSlotTakes(player, pickup);
            company.done.remove(PICKUP_NODE);
            Companies.save(source.getServer());
            player.closeContainer();
            expect(source, "the built-in grid cannot be taken out", gridStays, state(player));
            expect(source, "an upgrade fits only once its MAM research is done", Boolean.FALSE.equals(before) && Boolean.TRUE.equals(after),
                "before " + before + ", after " + after + " (survival " + !player.hasInfiniteMaterials() + ")");
            // an extra backpack is unpacked into the inventory and is gone
            ItemStack extra = TravelersBackpackCompat.newBackpack(0);
            extra.set(ModDataComponents.BACKPACK_CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.EMERALD, 3))));
            player.getInventory().setItem(10, extra);
            TravelersBackpackCompat.sync(player);
            int emeralds = player.getInventory().countItem(Items.EMERALD);
            boolean noItem = true;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) if (TravelersBackpackCompat.isBackpack(player.getInventory().getItem(i))) noItem = false;
            expect(source, "an extra backpack is unpacked and gone", emeralds == 3 && noItem && TravelersBackpackCompat.wearing(player), emeralds + " emeralds");
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) if (player.getInventory().getItem(i).is(Items.EMERALD)) player.getInventory().setItem(i, ItemStack.EMPTY);
            // milestone payments count and take what the backpack holds
            Milestone.Cost rods = Milestones.get("hub_upgrade_1").cost().get(0);
            storage = TravelersBackpackCompat.storage(player);
            storage.setStackInSlot(0, ItemStack.EMPTY);
            storage.setStackInSlot(5, new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(rods.key())), 10));
            int carried = rods.carried(player.getInventory());
            int took = rods.take(player.getInventory(), 4);
            int leftIn = storage.getStackInSlot(5).getCount();
            expect(source, "milestones and the Workshop take parts from the backpack", carried == 10 && took == 4 && leftIn == 6,
                "counted " + carried + ", took " + took + ", left " + leftIn);
            storage.setStackInSlot(5, ItemStack.EMPTY);
        }
        return 1;
    }

    /** Asks the open backpack window's first free upgrade slot whether it takes the stack; null when there is none. */
    private static Boolean freeUpgradeSlotTakes(ServerPlayer player, ItemStack stack) {
        for (Slot slot : player.containerMenu.slots) {
            if (slot instanceof UpgradeLockableSlotItemHandler upgradeSlot && !upgradeSlot.hasItem()) return upgradeSlot.mayPlace(stack);
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------ crafting

    /** Opens the worn backpack with its crafting tab open; returns the menu, or null. */
    private static BackpackBaseMenu openWithGrid(ServerPlayer player) {
        BackpackWrapper wrapper = TravelersBackpackCompat.wrapper(player);
        int slot = TravelersBackpackCompat.gridSlot(player);
        if (wrapper == null || slot < 0) return null;
        ServerActions.modifyUpgradeTab(wrapper, slot, true, ServerActions.TAB_OPEN);
        player.closeContainer();
        BackpackContainer.openBackpack(player, TravelersBackpackCompat.worn(player), Reference.WEARABLE_SCREEN_ID);
        return player.containerMenu instanceof BackpackBaseMenu menu ? menu : null;
    }

    /** Lays the planks into the grid (true in pattern = a plank) and returns what it makes. */
    private static ItemStack craft(ServerPlayer player, BackpackBaseMenu menu, boolean[] pattern) {
        CraftingUpgrade upgrade = menu.getWrapper().getUpgradeManager().getUpgrade(CraftingUpgrade.class).orElse(null);
        if (upgrade == null) return ItemStack.EMPTY;
        // laid as a player would: only where the grid takes them
        for (int i = 0; i < 9; i++) {
            Slot slot = menu.slots.get(menu.CRAFTING_GRID_START + i);
            boolean plank = pattern[i] && slot.mayPlace(new ItemStack(Items.OAK_PLANKS));
            upgrade.craftSlots.setItem(i, plank ? new ItemStack(Items.OAK_PLANKS) : ItemStack.EMPTY);
        }
        menu.slotChangedCraftingGrid(upgrade, player.level(), player);
        menu.broadcastChanges();
        return upgrade.resultSlots.getItem(0).copy();
    }

    private static int activeGridSlots(BackpackBaseMenu menu, boolean[] mayTakePlank) {
        int active = 0;
        for (int i = 0; i < 9 && menu.CRAFTING_GRID_START + i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(menu.CRAFTING_GRID_START + i);
            if (slot.isActive()) active++;
            mayTakePlank[i] = slot.mayPlace(new ItemStack(Items.OAK_PLANKS));
        }
        return active;
    }

    private static final boolean[] TABLE = {true, true, false, true, true, false, false, false, false};
    private static final boolean[] CHEST = {true, true, true, true, false, true, true, true, true};

    private static int craftGrid(CommandSourceStack source) {
        for (ServerPlayer player : players(source)) {
            BackpackBaseMenu menu = openWithGrid(player);
            if (menu == null) {
                expect(source, "the backpack has a crafting grid", false, state(player));
                continue;
            }
            boolean[] takes = new boolean[9];
            int active = activeGridSlots(menu, takes);
            boolean cornerTakes = takes[2] || takes[6] || takes[8];
            expect(source, "the built-in grid is 2x2: four slots, none in the third row or column", active == 4 && !cornerTakes && takes[0] && takes[4], active + " slots");
            ItemStack chest = craft(player, menu, CHEST);
            ItemStack table = craft(player, menu, TABLE);
            expect(source, "2x2 crafting in the backpack makes a crafting table from four planks", table.is(Items.CRAFTING_TABLE), table.toString());
            expect(source, "and has no 3x3 recipes (the chest's ring of planks makes nothing there)", !chest.is(Items.CHEST), chest.toString());
        }
        return 1;
    }

    private static int craftFull(CommandSourceStack source) {
        for (ServerPlayer player : players(source)) {
            Company company = Companies.of(player);
            company.done.add(CRAFTING_NODE);
            Companies.save(source.getServer());
            player.closeContainer();
            ItemStack upgrade = new ItemStack(ModItems.CRAFTING_UPGRADE);
            // what using it does (right-click)
            boolean installed = TravelersBackpackCompat.installCraftingUpgrade(player, upgrade);
            expect(source, "using a Crafting Upgrade turns the grid into 3x3, and uses the upgrade up",
                installed && TravelersBackpackCompat.gridSize(player) == 3 && (upgrade.isEmpty() || player.hasInfiniteMaterials()), state(player));
            BackpackBaseMenu menu = openWithGrid(player);
            if (menu == null) continue;
            boolean[] takes = new boolean[9];
            int active = activeGridSlots(menu, takes);
            ItemStack chest = craft(player, menu, CHEST);
            expect(source, "3x3 crafting in the backpack makes a chest", active == 9 && chest.is(Items.CHEST), active + " slots, " + chest);
            expect(source, "a second Crafting Upgrade is not used up", !TravelersBackpackCompat.installCraftingUpgrade(player, new ItemStack(ModItems.CRAFTING_UPGRADE)), "");
        }
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ the death crate

    private static long key(int where, int slot) {
        return ((long) where << 32) | (slot & 0xffffffffL);
    }

    private static int crateSetup(CommandSourceStack source) {
        PUT.clear();
        for (ServerPlayer player : players(source)) {
            player.closeContainer();
            clearAll(player);
            Inventory inventory = player.getInventory();
            put(inventory, DeathCrateBlockEntity.INVENTORY, 0, new ItemStack(Items.IRON_INGOT, 5));
            put(inventory, DeathCrateBlockEntity.INVENTORY, 7, new ItemStack(Items.TORCH, 12));
            put(inventory, DeathCrateBlockEntity.INVENTORY, 20, new ItemStack(Items.COBBLESTONE, 33));
            put(inventory, DeathCrateBlockEntity.INVENTORY, 36, new ItemStack(Items.IRON_BOOTS));
            put(inventory, DeathCrateBlockEntity.INVENTORY, 39, new ItemStack(Items.IRON_HELMET));
            put(inventory, DeathCrateBlockEntity.INVENTORY, Inventory.SLOT_OFFHAND, new ItemStack(Items.SHIELD));
            BackpackWrapper wrapper = TravelersBackpackCompat.wrapper(player);
            if (wrapper != null) {
                put(wrapper.getStorage(), 1, 3, new ItemStack(Items.DIAMOND, 2));
                put(wrapper.getStorage(), 1, wrapper.getStorage().getSlots() - 1, new ItemStack(Items.OAK_PLANKS, 16));
                if (wrapper.getTools() != null && wrapper.getTools().getSlots() > 0) put(wrapper.getTools(), 2, 0, new ItemStack(Items.IRON_PICKAXE));
            }
            report(source, "crate setup: " + PUT.size() + " stacks in known slots (hotbar, inventory, armour, offhand, backpack storage and tools); " + state(player));
        }
        return 1;
    }

    private static void put(Container container, int where, int slot, ItemStack stack) {
        container.setItem(slot, stack.copy());
        PUT.put(key(where, slot), stack.copy());
    }

    private static ServerPlayer alive(CommandSourceStack source, ServerPlayer player) {
        if (player.isDeadOrDying() || player.isRemoved()) {
            // the client did not press Respawn: do what that button does
            return source.getServer().getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
        }
        return player;
    }

    /** The player's crate markers and the crates under them. */
    private static Map<MapMarker, DeathCrateBlockEntity> crates(MinecraftServer server, String owner) {
        Map<MapMarker, DeathCrateBlockEntity> out = new LinkedHashMap<>();
        for (MapMarker m : MapMarkers.get(server).all()) {
            if (!m.death || !m.crate || (owner != null && !m.owner.equals(owner))) continue;
            ServerLevel level = null;
            for (ServerLevel l : server.getAllLevels()) if (l.dimension().identifier().toString().equals(m.dimension)) level = l;
            if (level == null) continue;
            BlockPos pos = new BlockPos(m.x, m.y, m.z);
            level.getChunk(pos);
            out.put(m, level.getBlockEntity(pos) instanceof DeathCrateBlockEntity crate ? crate : null);
        }
        return out;
    }

    private static int crateCheck(CommandSourceStack source) {
        for (ServerPlayer p : players(source)) {
            ServerPlayer player = alive(source, p);
            int carried = DeathCrates.carried(player, false).size();
            expect(source, "after dying the player carries nothing, and keeps the backpack with its grid",
                carried == 0 && TravelersBackpackCompat.wearing(player) && TravelersBackpackCompat.gridSize(player) > 0, carried + " stacks carried; " + state(player));
            Map<MapMarker, DeathCrateBlockEntity> crates = crates(source.getServer(), player.getUUID().toString());
            DeathCrateBlockEntity crate = null;
            MapMarker marker = null;
            for (var e : crates.entrySet()) {
                if (e.getValue() != null && e.getValue().entries.size() == PUT.size()) {
                    crate = e.getValue();
                    marker = e.getKey();
                }
            }
            boolean grid = false;
            if (crate != null) for (DeathCrateBlockEntity.Entry e : crate.entries) grid |= TravelersBackpackCompat.isGrid(e.stack());
            expect(source, "a Death Crate where they died holds every stack, and the map shows it as their death marker",
                crate != null && marker != null && !grid, crates.size() + " crate markers" + (crate == null ? "" : ", crate at " + crate.getBlockPos()));
            if (crate == null) continue;
            BlockPos at = crate.getBlockPos();
            String markerId = marker.id;
            DeathCrates.open(player, at);
            int right = 0;
            List<String> wrong = new ArrayList<>();
            BackpackWrapper wrapper = TravelersBackpackCompat.wrapper(player);
            for (var e : PUT.entrySet()) {
                int where = (int) (e.getKey() >> 32), slot = (int) (long) e.getKey();
                Container c = where == 0 ? player.getInventory() : where == 1 ? wrapper.getStorage() : wrapper.getTools();
                if (ItemStack.matches(c.getItem(slot), e.getValue())) right++;
                else wrong.add(where + "/" + slot + ": " + c.getItem(slot));
            }
            boolean gone = !(player.level().getBlockEntity(at) instanceof DeathCrateBlockEntity) && MapMarkers.get(source.getServer()).byId(markerId) == null;
            expect(source, "one click on the crate puts every stack back in its own slot", right == PUT.size(), right + " of " + PUT.size() + " " + wrong);
            expect(source, "the emptied crate and its marker are gone", gone, "");
        }
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ fixes

    private static int fixes(CommandSourceStack source) {
        for (ServerPlayer player : players(source)) {
            leftoverSaved(source, player);
            crateBuildGunAndGrid(source, player);
        }
        return 1;
    }

    /** 3: armour shift-clicked out of a backpack slot that keeps part of its stack: the part left is saved in the backpack. */
    private static void leftoverSaved(CommandSourceStack source, ServerPlayer player) {
        player.closeContainer();
        clearAll(player);
        BackpackWrapper wrapper = TravelersBackpackCompat.wrapper(player);
        if (wrapper == null) {
            expect(source, "fix 3: a backpack is worn", false, state(player));
            return;
        }
        // a stack of two of something worn on the head: armour stacks to one, so a carved pumpkin (it stacks to 64)
        ItemStack two = new ItemStack(Items.CARVED_PUMPKIN, 2);
        wrapper.getStorage().setStackInSlot(0, two);
        BackpackContainer.openBackpack(player, TravelersBackpackCompat.worn(player), Reference.WEARABLE_SCREEN_ID);
        if (!(player.containerMenu instanceof BackpackBaseMenu menu)) {
            expect(source, "fix 3: the backpack window opens", false, player.containerMenu.getClass().getSimpleName());
            return;
        }
        int index = -1;
        for (int i = menu.BACKPACK_INV_START; i < menu.BACKPACK_INV_END && i < menu.slots.size(); i++) {
            if (menu.slots.get(i).getItem().is(Items.CARVED_PUMPKIN)) index = i;
        }
        if (index >= 0) menu.quickMoveStack(player, index);
        player.closeContainer();
        ItemStack worn = TravelersBackpackCompat.worn(player);
        int saved = 0;
        for (ItemStack stack : ContainerContentsHelper.getItems(worn.getOrDefault(ModDataComponents.BACKPACK_CONTAINER, ItemContainerContents.EMPTY),
            wrapper.getStorage().getSlots())) {
            if (stack.is(Items.CARVED_PUMPKIN)) saved += stack.getCount();
        }
        boolean on = player.getItemBySlot(EquipmentSlot.HEAD).is(Items.CARVED_PUMPKIN);
        expect(source, "fix 3: shift-clicking armour out of a backpack slot that keeps part of the stack saves the part left in the backpack",
            index >= 0 && on && saved == 1, "slot " + index + ", pumpkin on " + on + ", the backpack's saved contents hold " + saved + " (1 wanted)");
        clearAll(player);
        player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
    }

    /** 4: a Build Gun in the backpack is left out of the crate; the crafting grid's stacks go in and come back to the grid. */
    private static void crateBuildGunAndGrid(CommandSourceStack source, ServerPlayer player) {
        player.closeContainer();
        clearAll(player);
        BackpackWrapper wrapper = TravelersBackpackCompat.wrapper(player);
        Container grid = TravelersBackpackCompat.grid(player);
        if (wrapper == null || grid == null) {
            expect(source, "fix 4: the backpack and its crafting grid are there", false, state(player));
            return;
        }
        wrapper.getStorage().setStackInSlot(2, new ItemStack(com.meakaandre.siftec.registry.ModItems.BUILD_GUN.get()));
        wrapper.getStorage().setStackInSlot(3, new ItemStack(Items.COAL, 7));
        grid.setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
        List<DeathCrateBlockEntity.Entry> looked = DeathCrates.carried(player, false);
        boolean gun = false, planks = false;
        for (DeathCrateBlockEntity.Entry e : looked) {
            gun |= e.stack().getItem() instanceof BuildGunItem;
            planks |= e.where() == 3 && e.slot() == 0 && e.stack().is(Items.OAK_PLANKS) && e.stack().getCount() == 3;
        }
        expect(source, "fix 4: the crate leaves out a Build Gun kept in the backpack", !gun, looked.size() + " stacks");
        expect(source, "fix 4: the crate takes what lies on the backpack's crafting grid", planks, looked.toString());
        // as at death: everything is taken (the Build Gun too, it comes back on respawn), into a crate the owner opens
        List<DeathCrateBlockEntity.Entry> taken = DeathCrates.carried(player, true);
        boolean emptied = grid.getItem(0).isEmpty() && wrapper.getStorage().getStackInSlot(2).isEmpty();
        ServerLevel level = player.level();
        BlockPos at = DeathCrates.spot(level, player.blockPosition().above(3));
        if (at == null) {
            expect(source, "fix 4: room for a crate", false, "");
            return;
        }
        level.setBlockAndUpdate(at, ModBlocks.DEATH_CRATE.get().defaultBlockState());
        if (!(level.getBlockEntity(at) instanceof DeathCrateBlockEntity crate)) {
            expect(source, "fix 4: a crate goes down", false, at.toShortString());
            return;
        }
        crate.owner = player.getUUID();
        crate.ownerName = player.getGameProfile().name();
        crate.entries.addAll(taken);
        int left = DeathCrates.restore(player, crate);
        boolean back = grid.getItem(0).is(Items.OAK_PLANKS) && grid.getItem(0).getCount() == 3 && wrapper.getStorage().getStackInSlot(3).is(Items.COAL);
        expect(source, "fix 4: opened, the crate puts the grid's planks back on the grid", emptied && back && left == 0,
            "taken " + taken.size() + ", emptied " + emptied + ", grid now " + grid.getItem(0) + ", " + left + " left in the crate");
        if (level.getBlockEntity(at) instanceof DeathCrateBlockEntity still) {
            still.entries.clear();
            level.removeBlock(at, false);
        }
        grid.setItem(0, ItemStack.EMPTY);
        clearAll(player);
    }

    // ------------------------------------------------------------------------------------------------ the crafting grid's locks

    /** The Flywheel: crafted by hand (siftec:crafting/flywheel), locked to HUB Upgrade 6. */
    private static final String FLYWHEEL_LOCK = "hub_upgrade_6";

    private static int fixes2(CommandSourceStack source) {
        for (ServerPlayer player : players(source)) gridLocks(source, player);
        return 1;
    }

    /** How many of the item the player carries, inventory and backpack. */
    private static int carried(ServerPlayer player, Item item) {
        int n = player.getInventory().countItem(item);
        ItemStackHandler storage = TravelersBackpackCompat.storage(player);
        if (storage != null) for (int i = 0; i < storage.getSlots(); i++) if (storage.getStackInSlot(i).is(item)) n += storage.getStackInSlot(i).getCount();
        return n;
    }

    /**
     * A locked recipe on the backpack's 3x3 grid: its result shows, but a click does not take it and a shift-click
     * crafts nothing; once the company has the milestone both work.
     */
    private static void gridLocks(CommandSourceStack source, ServerPlayer player) {
        Company company = Companies.of(player);
        boolean hadLock = company.done.contains(FLYWHEEL_LOCK);
        company.done.add(CRAFTING_NODE);
        company.done.remove(FLYWHEEL_LOCK);
        Companies.save(source.getServer());
        player.closeContainer();
        clearAll(player);
        if (TravelersBackpackCompat.gridSize(player) < 3) TravelersBackpackCompat.installCraftingUpgrade(player, new ItemStack(ModItems.CRAFTING_UPGRADE));
        BackpackBaseMenu menu = openWithGrid(player);
        CraftingUpgrade upgrade = menu == null ? null : menu.getWrapper().getUpgradeManager().getUpgrade(CraftingUpgrade.class).orElse(null);
        Slot result = null;
        if (menu != null) for (Slot slot : menu.slots) if (slot instanceof ResultSlotExt) result = slot;
        if (menu == null || upgrade == null || result == null || TravelersBackpackCompat.gridSize(player) < 3) {
            expect(source, "fixes2: the backpack has its 3x3 crafting grid open", false, state(player));
            restoreLock(source, company, hadLock);
            return;
        }
        Item flywheel = BuiltInRegistries.ITEM.getValue(Identifier.parse("create:flywheel"));
        Item rod = BuiltInRegistries.ITEM.getValue(Identifier.parse("siftec:iron_rod"));
        Item sheet = BuiltInRegistries.ITEM.getValue(Identifier.parse("create:iron_sheet"));
        Item shaft = BuiltInRegistries.ITEM.getValue(Identifier.parse("create:shaft"));
        // RSR / SAS / RSR, two of everything so one craft leaves a second one on the grid
        Item[] pattern = {rod, sheet, rod, sheet, shaft, sheet, rod, sheet, rod};
        for (int i = 0; i < 9; i++) upgrade.craftSlots.setItem(i, new ItemStack(pattern[i], 2));
        menu.slotChangedCraftingGrid(upgrade, player.level(), player);
        menu.broadcastChanges();
        ItemStack shown = result.getItem().copy();
        boolean survival = !player.hasInfiniteMaterials();
        expect(source, "fixes2: a locked recipe's result shows on the backpack's grid", shown.is(flywheel), shown + (survival ? "" : " (creative: locks do not apply)"));
        boolean mayPickup = result.mayPickup(player);
        int index = menu.slots.indexOf(result);
        menu.clicked(index, 0, ContainerInput.PICKUP, player);
        boolean clickTook = menu.getCarried().is(flywheel);
        menu.setCarried(ItemStack.EMPTY);
        menu.quickMoveStack(player, index);
        int made = carried(player, flywheel);
        boolean gridKept = upgrade.craftSlots.getItem(4).is(shaft) && upgrade.craftSlots.getItem(4).getCount() == 2;
        expect(source, "fixes2: a locked result cannot be taken out of the backpack's grid by a click", survival && !mayPickup && !clickTook,
            "mayPickup " + mayPickup + ", click took it " + clickTook);
        expect(source, "fixes2: nor made by shift-crafting, and the grid keeps its items", survival && made == 0 && gridKept,
            made + " flywheels made, shaft slot " + upgrade.craftSlots.getItem(4));
        // once unlocked, the same grid crafts
        company.done.add(FLYWHEEL_LOCK);
        menu.slotChangedCraftingGrid(upgrade, player.level(), player);
        boolean mayNow = result.mayPickup(player);
        menu.quickMoveStack(player, index);
        int madeNow = carried(player, flywheel);
        expect(source, "fixes2: once the milestone is done, the grid's result can be taken and shift-crafted", mayNow && madeNow >= 1,
            "mayPickup " + mayNow + ", " + madeNow + " flywheels made");
        for (int i = 0; i < 9; i++) upgrade.craftSlots.setItem(i, ItemStack.EMPTY);
        menu.slotChangedCraftingGrid(upgrade, player.level(), player);
        player.closeContainer();
        clearAll(player);
        restoreLock(source, company, hadLock);
    }

    private static void restoreLock(CommandSourceStack source, Company company, boolean had) {
        if (had) company.done.add(FLYWHEEL_LOCK);
        else company.done.remove(FLYWHEEL_LOCK);
        Companies.save(source.getServer());
    }

    private static int crateKeep(CommandSourceStack source) {
        for (ServerPlayer player : players(source)) {
            player.closeContainer();
            clearAll(player);
            player.getInventory().setItem(4, new ItemStack(Items.GOLD_INGOT, 9));
            ItemStackHandler storage = TravelersBackpackCompat.storage(player);
            if (storage != null) storage.setStackInSlot(1, new ItemStack(Items.EMERALD, 4));
            report(source, "crate keep: gold in the hotbar and emeralds in the backpack, for a crate left alone");
        }
        return 1;
    }

    private static int crateKept(CommandSourceStack source, boolean afterRestart) {
        Map<MapMarker, DeathCrateBlockEntity> crates = crates(source.getServer(), null);
        int full = 0;
        for (DeathCrateBlockEntity crate : crates.values()) if (crate != null && !crate.isEmpty()) full++;
        expect(source, afterRestart ? "after a restart the crate left alone is still there, full, with its death marker"
            : "a crate left alone stays where it is, with its death marker", full >= 1 && full == crates.size(), full + " full crates of " + crates.size() + " crate markers");
        return 1;
    }
}
