package com.meakaandre.siftec.compat;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.backpack.Backpack;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.hub.Locks;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.tiviacz.travelersbackpack.attachment.AttachmentUtils;
import com.tiviacz.travelersbackpack.attachment.BackpackAttachment;
import com.tiviacz.travelersbackpack.common.ServerActions;
import com.tiviacz.travelersbackpack.init.ModDataComponents;
import com.tiviacz.travelersbackpack.init.ModItems;
import com.tiviacz.travelersbackpack.inventory.BackpackContainer;
import com.tiviacz.travelersbackpack.inventory.BackpackWrapper;
import com.tiviacz.travelersbackpack.inventory.handler.ItemStackHandler;
import com.tiviacz.travelersbackpack.inventory.menu.slot.UpgradeLockableSlotItemHandler;
import com.tiviacz.travelersbackpack.item.TravelersBackpackItem;
import com.tiviacz.travelersbackpack.util.BackpackDeathHelper;
import com.tiviacz.travelersbackpack.util.Reference;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * For the smoke test ({@code /siftec selftest tb ...}), on every player online:
 * <ul>
 * <li>{@code setup}: back to a new player: no backpack milestone done, no backpack worn;</li>
 * <li>{@code give}: the company finishes HUB Upgrade 1, and the player has exactly one backpack, worn, Leather;</li>
 * <li>{@code tier <n>}: the company finishes the milestones up to tier n (0 Leather to 4 Netherite);</li>
 * <li>{@code rules}: no backpack recipe is left, unequipping, swapping and placing are refused, a backpack item in the
 * inventory goes back on the player's back, an extra one is unpacked, old backpack rows are emptied into it,
 * payments count what it holds;</li>
 * <li>{@code offline}: the company reaches the next tier while the player's checks are held (as if offline); the
 * tier is applied when the join check runs;</li>
 * <li>{@code locked}: an upgrade the company has not unlocked can neither be taken from a crafting grid nor put in
 * an upgrade slot, and can once it is unlocked;</li>
 * <li>{@code upgrade crafting}: installs the Crafting Upgrade with its tab open (for a screenshot);</li>
 * <li>{@code death setup} / {@code death check}: items in the backpack before dying, and after respawning.</li>
 * </ul>
 */
public final class TravelersBackpackSelfTest {
    /** Recipes of Traveler's Backpack that stay: the upgrades and what goes into them. */
    private static final Set<String> KEPT = Set.of("backpack_tank", "blank_upgrade", "hose", "hose_nozzle", "crafting_upgrade", "furnace_upgrade",
        "smoker_upgrade", "blast_furnace_upgrade", "pickup_upgrade", "magnet_upgrade", "void_upgrade", "feeding_upgrade", "refill_upgrade",
        "tanks_upgrade", "jukebox_upgrade", "lantern_upgrade");
    /** While true the once-a-second check skips players, as if they were offline. */
    static volatile boolean held;

    private TravelersBackpackSelfTest() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("tb")
                    .then(Commands.literal("setup").executes(context -> setup(context.getSource())))
                    .then(Commands.literal("give").executes(context -> give(context.getSource())))
                    .then(Commands.literal("tier").then(Commands.argument("n", IntegerArgumentType.integer(-1, 4))
                        .executes(context -> tier(context.getSource(), IntegerArgumentType.getInteger(context, "n")))))
                    .then(Commands.literal("rules").executes(context -> rules(context.getSource())))
                    .then(Commands.literal("offline").executes(context -> offline(context.getSource())))
                    .then(Commands.literal("locked").executes(context -> locked(context.getSource())))
                    .then(Commands.literal("upgrade").then(Commands.literal("crafting").executes(context -> crafting(context.getSource()))))
                    .then(Commands.literal("death")
                        .then(Commands.literal("setup").executes(context -> deathSetup(context.getSource())))
                        .then(Commands.literal("check").executes(context -> deathCheck(context.getSource())))))))
        ));
    }

    private static List<ServerPlayer> players(CommandSourceStack source) {
        return List.copyOf(source.getServer().getPlayerList().getPlayers());
    }

    /** The milestones that give or raise the backpack, by tier ordinal. */
    private static List<Milestone> tierMilestones() {
        List<Milestone> out = new ArrayList<>();
        for (String token : TravelersBackpackCompat.TIER_TOKENS) {
            Milestone found = null;
            for (Milestone m : Milestones.all()) if (m.tokens().contains(token)) found = m;
            out.add(found);
        }
        return out;
    }

    /** The company has done exactly the backpack milestones up to tier n (-1: none). */
    private static void setTier(CommandSourceStack source, Company company, int n) {
        List<Milestone> tiers = tierMilestones();
        for (int t = 0; t < tiers.size(); t++) {
            if (tiers.get(t) == null) continue;
            if (t <= n) company.done.add(tiers.get(t).id());
            else company.done.remove(tiers.get(t).id());
        }
        Companies.save(source.getServer());
    }

    private static int backpackItems(ServerPlayer player) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) if (TravelersBackpackCompat.isBackpack(player.getInventory().getItem(i))) n++;
        if (TravelersBackpackCompat.isBackpack(player.containerMenu.getCarried())) n++;
        return n;
    }

    private static String state(ServerPlayer player) {
        ItemStack worn = TravelersBackpackCompat.worn(player);
        ItemStackHandler storage = TravelersBackpackCompat.storage(player);
        return "wearing " + TravelersBackpackCompat.wearing(player) + " (" + (worn.isEmpty() ? "-" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(worn.getItem()))
            + "), tier " + TravelersBackpackCompat.wornTier(player) + ", storage slots " + (storage == null ? 0 : storage.getSlots())
            + ", upgrade slots " + worn.getOrDefault(ModDataComponents.UPGRADE_SLOTS, 0) + ", backpack items in inventory " + backpackItems(player);
    }

    private static int setup(CommandSourceStack source) {
        for (ServerPlayer player : players(source)) {
            Company company = Companies.of(player);
            setTier(source, company, -1);
            AttachmentUtils.getAttachment(player).ifPresent(a -> a.remove(player));
            player.removeAttached(Backpack.CONTENTS);
            TravelersBackpackCompat.sync(player);
            boolean ok = !TravelersBackpackCompat.wearing(player) && backpackItems(player) == 0;
            report(source, "SELFTEST tb setup: before HUB Upgrade 1 " + state(player) + "; no backpack yet " + ok);
        }
        return 1;
    }

    private static int give(CommandSourceStack source) {
        for (ServerPlayer player : players(source)) {
            Company company = Companies.of(player);
            setTier(source, company, 0);
            TravelersBackpackCompat.sync(player);
            TravelersBackpackCompat.sync(player);
            boolean ok = TravelersBackpackCompat.wearing(player) && TravelersBackpackCompat.wornTier(player) == 0 && backpackItems(player) == 0
                && TravelersBackpackCompat.storage(player) != null && TravelersBackpackCompat.storage(player).getSlots() == 27;
            report(source, "SELFTEST tb give: after HUB Upgrade 1 " + state(player) + "; exactly one, worn, Leather " + ok);
        }
        return 1;
    }

    private static int tier(CommandSourceStack source, int n) {
        for (ServerPlayer player : players(source)) {
            Company company = Companies.of(player);
            int before = TravelersBackpackCompat.wornTier(player);
            setTier(source, company, n);
            TravelersBackpackCompat.sync(player);
            int want = Math.max(before, n);
            boolean ok = TravelersBackpackCompat.wornTier(player) == want;
            report(source, "SELFTEST tb tier " + n + ": company tier " + TravelersBackpackCompat.tierFor(company) + ", " + state(player)
                + "; raised (never lowered) " + ok);
        }
        return 1;
    }

    private static int rules(CommandSourceStack source) {
        // no recipe makes or upgrades a backpack; the upgrades' own recipes stay
        int left = 0, removedPresent = 0;
        List<String> odd = new ArrayList<>();
        for (var holder : source.getServer().getRecipeManager().getRecipes()) {
            Identifier rid = holder.id().identifier();
            if (!rid.getNamespace().equals("travelersbackpack")) continue;
            left++;
            if (Milestones.removedRecipes().contains(rid)) removedPresent++;
            if (!KEPT.contains(rid.getPath())) odd.add(rid.getPath());
        }
        report(source, "SELFTEST tb recipes: Traveler's Backpack recipes left " + left + " (all upgrades: " + odd.isEmpty() + " " + odd + "), removed ones present "
            + removedPresent + "; backpack not craftable " + (removedPresent == 0 && odd.isEmpty() && left == KEPT.size()));
        for (ServerPlayer player : players(source)) {
            TravelersBackpackCompat.sync(player);
            BackpackAttachment attachment = AttachmentUtils.getAttachment(player).orElseThrow();
            if (!attachment.hasBackpack()) {
                report(source, "SELFTEST tb rules: " + player.getGameProfile().name() + " wears no backpack (run tb give first)");
                continue;
            }
            ItemStackHandler storage = TravelersBackpackCompat.storage(player);
            storage.setStackInSlot(0, new ItemStack(Items.GOLD_INGOT, 5));
            // unequip and swap are refused
            boolean unequip = ServerActions.unequipBackpack(player);
            player.setItemInHand(InteractionHand.MAIN_HAND, TravelersBackpackCompat.newBackpack(0));
            boolean swap = ServerActions.swapBackpack(player);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            boolean still = TravelersBackpackCompat.wearing(player) && backpackItems(player) == 0;
            // placing as a block is refused
            BlockPos pos = player.blockPosition().above(3);
            ItemStack copy = attachment.getBackpack().copy();
            InteractionResult placed = ((TravelersBackpackItem) copy.getItem()).place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, copy,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos.below(), false)));
            boolean noBlock = !(player.level().getBlockState(pos).getBlock() instanceof com.tiviacz.travelersbackpack.block.TravelersBackpackBlock);
            // the death handler keeps it on
            boolean dropOnDeath = BackpackDeathHelper.onPlayerDrops(player.level(), player, attachment.getBackpack());
            report(source, "SELFTEST tb bound: unequip " + unequip + ", swap " + swap + ", still worn and no backpack item " + still + "; place " + placed
                + ", no block " + noBlock + "; death would drop or place it " + dropOnDeath + "; bound "
                + (!unequip && !swap && still && placed == InteractionResult.FAIL && noBlock && !dropOnDeath));
            // a backpack item off the player's back goes back on, with its contents
            ItemStack own = attachment.getBackpack().copy();
            attachment.remove(player);
            player.getInventory().setItem(9, own);
            TravelersBackpackCompat.sync(player);
            ItemStackHandler back = TravelersBackpackCompat.storage(player);
            boolean returned = TravelersBackpackCompat.wearing(player) && backpackItems(player) == 0 && back != null && back.getStackInSlot(0).is(Items.GOLD_INGOT)
                && back.getStackInSlot(0).getCount() == 5;
            // an extra backpack is unpacked into the inventory and is gone
            ItemStack extra = TravelersBackpackCompat.newBackpack(0);
            extra.set(ModDataComponents.BACKPACK_CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new ItemStack(Items.EMERALD, 3))));
            player.getInventory().setItem(10, extra);
            player.containerMenu.setCarried(TravelersBackpackCompat.newBackpack(1));
            TravelersBackpackCompat.sync(player);
            int emeralds = player.getInventory().countItem(Items.EMERALD);
            boolean unpacked = backpackItems(player) == 0 && emeralds == 3 && TravelersBackpackCompat.wearing(player);
            report(source, "SELFTEST tb strays: own backpack put back on with its gold " + returned + "; extra backpacks unpacked (emeralds " + emeralds + ") and gone "
                + unpacked + "; " + state(player));
            removeAll(player, stack -> stack.is(Items.EMERALD));
            // the old SIFTEC backpack rows are emptied into it
            Backpack.Contents old = player.getAttachedOrCreate(Backpack.CONTENTS);
            old.items.set(3, new ItemStack(Items.DIAMOND, 4));
            old.items.set(30, new ItemStack(Items.REDSTONE, 20));
            TravelersBackpackCompat.sync(player);
            int diamonds = 0, redstone = 0;
            ItemStackHandler now = TravelersBackpackCompat.storage(player);
            for (int i = 0; i < now.getSlots(); i++) {
                if (now.getStackInSlot(i).is(Items.DIAMOND)) diamonds += now.getStackInSlot(i).getCount();
                if (now.getStackInSlot(i).is(Items.REDSTONE)) redstone += now.getStackInSlot(i).getCount();
            }
            boolean migrated = diamonds == 4 && redstone == 20 && player.getAttached(Backpack.CONTENTS) == null;
            report(source, "SELFTEST tb migrate: old rows moved into the Traveler's Backpack: diamonds " + diamonds + ", redstone " + redstone
                + ", old store gone " + (player.getAttached(Backpack.CONTENTS) == null) + "; ok " + migrated);
            // the HUB counts and takes what the backpack holds
            Milestone.Cost rods = Milestones.get("hub_upgrade_1").cost().get(0);
            for (int i = 0; i < now.getSlots(); i++) now.setStackInSlot(i, ItemStack.EMPTY);
            removeAll(player, rods::matches);
            now.setStackInSlot(5, new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Identifier.parse(rods.key())), 10));
            int carried = rods.carried(player.getInventory());
            int took = rods.take(player.getInventory(), 4);
            int leftInPack = now.getStackInSlot(5).getCount();
            report(source, "SELFTEST tb pay: " + rods.key() + " counted from the backpack " + carried + ", taken " + took + ", left in it " + leftInPack
                + "; ok " + (carried == 10 && took == 4 && leftInPack == 6));
            now.setStackInSlot(5, ItemStack.EMPTY);
        }
        return 1;
    }

    private static int offline(CommandSourceStack source) {
        for (ServerPlayer player : players(source)) {
            Company company = Companies.of(player);
            int before = TravelersBackpackCompat.wornTier(player);
            if (before < 0 || before >= 4) {
                report(source, "SELFTEST tb offline: needs a worn backpack below Netherite, tier is " + before);
                continue;
            }
            held = true;
            try {
                setTier(source, company, before + 1);
                int whileAway = TravelersBackpackCompat.wornTier(player);
                // what runs when the player joins again
                TravelersBackpackCompat.sync(player);
                int after = TravelersBackpackCompat.wornTier(player);
                report(source, "SELFTEST tb offline: company reached tier " + (before + 1) + " while away; worn tier while away " + whileAway
                    + ", after the join check " + after + "; applied at login " + (whileAway == before && after == before + 1));
            } finally {
                held = false;
            }
        }
        return 1;
    }

    private static int locked(CommandSourceStack source) {
        Milestone lock = Locks.lockOf(ModItems.CRAFTING_UPGRADE);
        for (ServerPlayer player : players(source)) {
            Company company = Companies.of(player);
            if (lock == null || !TravelersBackpackCompat.wearing(player)) {
                report(source, "SELFTEST tb locked: crafting upgrade lock " + (lock == null ? "none" : lock.id()) + ", wearing " + TravelersBackpackCompat.wearing(player));
                continue;
            }
            ItemStack upgrade = new ItemStack(ModItems.CRAFTING_UPGRADE);
            boolean had = company.done.remove(lock.id());
            boolean takeBefore = Locks.allowed(player, upgrade);
            Boolean slotBefore = slotMayPlace(player, upgrade);
            company.done.add(lock.id());
            boolean takeAfter = Locks.allowed(player, upgrade);
            Boolean slotAfter = slotMayPlace(player, upgrade);
            if (!had) company.done.remove(lock.id());
            Companies.save(source.getServer());
            report(source, "SELFTEST tb locked: crafting upgrade needs " + lock.id() + "; before it: craftable " + takeBefore + ", fits an upgrade slot " + slotBefore
                + "; after: craftable " + takeAfter + ", fits " + slotAfter + "; ok " + (!takeBefore && Boolean.FALSE.equals(slotBefore) && takeAfter && Boolean.TRUE.equals(slotAfter))
                + " (survival " + !player.hasInfiniteMaterials() + ")");
        }
        return 1;
    }

    /** Opens the worn backpack's window and asks its first upgrade slot; null when there is none. */
    private static Boolean slotMayPlace(ServerPlayer player, ItemStack stack) {
        BackpackContainer.openBackpack(player, TravelersBackpackCompat.worn(player), Reference.WEARABLE_SCREEN_ID);
        Boolean result = null;
        for (Slot slot : player.containerMenu.slots) {
            if (slot instanceof UpgradeLockableSlotItemHandler upgradeSlot && !upgradeSlot.hasItem()) {
                result = upgradeSlot.mayPlace(stack);
                break;
            }
        }
        player.closeContainer();
        return result;
    }

    private static int crafting(CommandSourceStack source) {
        for (ServerPlayer player : players(source)) {
            BackpackAttachment attachment = AttachmentUtils.getAttachment(player).orElseThrow();
            BackpackWrapper wrapper = attachment.getWrapper();
            if (wrapper == null) {
                report(source, "SELFTEST tb upgrade: no backpack worn");
                continue;
            }
            ItemStack upgrade = new ItemStack(ModItems.CRAFTING_UPGRADE);
            upgrade.set(ModDataComponents.TAB_OPEN, true);
            wrapper.setStarterUpgrade(upgrade);
            attachment.equipBackpack(attachment.getBackpack().copy(), player);
            boolean installed = false;
            ItemStackHandler upgrades = attachment.getWrapper().getUpgrades();
            for (int i = 0; i < upgrades.getSlots(); i++) installed |= upgrades.getStackInSlot(i).is(ModItems.CRAFTING_UPGRADE);
            report(source, "SELFTEST tb upgrade crafting: installed " + installed + "; " + state(player));
        }
        return 1;
    }

    private static int deathSetup(CommandSourceStack source) {
        for (ServerPlayer player : players(source)) {
            ItemStackHandler storage = TravelersBackpackCompat.storage(player);
            if (storage == null) {
                report(source, "SELFTEST tb death setup: no backpack worn");
                continue;
            }
            storage.setStackInSlot(0, new ItemStack(Items.GOLD_INGOT, 7));
            storage.setStackInSlot(20, new ItemStack(Items.EMERALD, 3));
            player.getInventory().setItem(0, new ItemStack(Items.STICK, 2));
            report(source, "SELFTEST tb death setup: 7 gold and 3 emeralds in the backpack, 2 sticks in the hotbar; " + state(player));
        }
        return 1;
    }

    private static int deathCheck(CommandSourceStack source) {
        for (ServerPlayer player : players(source)) {
            boolean respawned = false;
            if (player.isDeadOrDying() || player.isRemoved()) {
                // the client did not press Respawn: do what that button does
                player = source.getServer().getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
                respawned = true;
            }
            ItemStackHandler storage = TravelersBackpackCompat.storage(player);
            int gold = 0, emerald = 0;
            if (storage != null) {
                for (int i = 0; i < storage.getSlots(); i++) {
                    if (storage.getStackInSlot(i).is(Items.GOLD_INGOT)) gold += storage.getStackInSlot(i).getCount();
                    if (storage.getStackInSlot(i).is(Items.EMERALD)) emerald += storage.getStackInSlot(i).getCount();
                }
            }
            int sticksOnGround = 0, backpacksOnGround = 0;
            for (var entity : player.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, player.getBoundingBox().inflate(64))) {
                if (entity.getItem().is(Items.STICK)) sticksOnGround += entity.getItem().getCount();
                if (TravelersBackpackCompat.isBackpack(entity.getItem())) backpacksOnGround++;
            }
            boolean ok = TravelersBackpackCompat.wearing(player) && gold == 7 && emerald == 3 && backpacksOnGround == 0;
            report(source, "SELFTEST tb death: respawned by the test " + respawned + ", dead now " + player.isDeadOrDying() + "; " + state(player)
                + "; in it gold " + gold + " emerald " + emerald + "; backpacks on the ground " + backpacksOnGround + ", sticks dropped " + sticksOnGround
                + "; survives death with its contents " + ok);
        }
        return 1;
    }

    private static void removeAll(ServerPlayer player, java.util.function.Predicate<ItemStack> which) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (which.test(player.getInventory().getItem(i))) player.getInventory().setItem(i, ItemStack.EMPTY);
        }
    }

    private static void report(CommandSourceStack source, String text) {
        Siftec.LOGGER.info(text);
        source.sendSuccess(() -> Component.literal(text), false);
    }
}
