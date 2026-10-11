package com.meakaandre.siftec.compat;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.backpack.Backpack;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.hub.Locks;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.tiviacz.travelersbackpack.attachment.AttachmentUtils;
import com.tiviacz.travelersbackpack.attachment.BackpackAttachment;
import com.tiviacz.travelersbackpack.component.RenderInfo;
import com.tiviacz.travelersbackpack.init.ModDataComponents;
import com.tiviacz.travelersbackpack.init.ModItems;
import com.tiviacz.travelersbackpack.inventory.BackpackWrapper;
import com.tiviacz.travelersbackpack.inventory.Tiers;
import com.tiviacz.travelersbackpack.inventory.handler.ItemStackHandler;
import com.tiviacz.travelersbackpack.item.TravelersBackpackItem;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.List;

/**
 * Traveler's Backpack as the pack's backpack. Loaded only when that mod is installed.
 * <ul>
 * <li>Given: every member of a company that has finished the milestone with the "backpack" token (HUB Upgrade 1) wears
 * one Traveler's Backpack; nobody can craft one (every backpack recipe is removed and the backpack items are switched
 * off, see tools/content.py).</li>
 * <li>Upgrades automatically: its tier is the highest "backpack:&lt;tier&gt;" token the company has finished; members who
 * were offline get theirs the next time they join.</li>
 * <li>Soulbound: it never leaves the player's back. Unequipping, swapping and placing it as a block are refused (mixins
 * in {@code mixin/tb}), it stays on through death with everything in it, and a backpack item found in a player's
 * inventory goes back on their back, or is unpacked when they already wear one.</li>
 * <li>Its upgrade items are locked by milestones like everything else, so a locked one cannot go into an upgrade slot.</li>
 * </ul>
 * Everything is checked once a second for every player online, and when a player joins.
 */
public final class TravelersBackpackCompat {
    /** The tokens that give the backpack (Leather) and raise it, by tier ordinal. */
    public static final List<String> TIER_TOKENS = List.of("backpack", "backpack:iron", "backpack:gold", "backpack:diamond", "backpack:netherite");

    private TravelersBackpackCompat() {
    }

    public static void register() {
        Backpack.worn = TravelersBackpackCompat::containers;
        ServerPlayerEvents.JOIN.register(TravelersBackpackCompat::sync);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 != 7 || TravelersBackpackSelfTest.held) return;
            for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) sync(player);
        });
        TravelersBackpackSelfTest.register();
        Siftec.LOGGER.info("SIFTEC: Traveler's Backpack found; each player is given one, raised by milestones and bound to them");
    }

    /** The tier the company's backpacks have (0 Leather to 4 Netherite), or -1 before the company gets them. */
    public static int tierFor(Company company) {
        if (company == null) return -1;
        int tier = -1;
        for (String id : company.done) {
            Milestone m = Milestones.get(id);
            if (m == null) continue;
            for (int t = 0; t < TIER_TOKENS.size(); t++) {
                if (m.tokens().contains(TIER_TOKENS.get(t))) tier = Math.max(tier, t);
            }
        }
        return tier;
    }

    public static int tierFor(ServerPlayer player) {
        return Companies.isFake(player) ? -1 : tierFor(Companies.find(player));
    }

    public static boolean wearing(Player player) {
        return AttachmentUtils.getAttachment(player).map(BackpackAttachment::hasBackpack).orElse(false);
    }

    public static ItemStack worn(Player player) {
        return AttachmentUtils.getAttachment(player).map(BackpackAttachment::getBackpack).orElse(ItemStack.EMPTY);
    }

    /** The tier of the backpack the player wears, or -1. */
    public static int wornTier(Player player) {
        ItemStack stack = worn(player);
        return stack.getItem() instanceof TravelersBackpackItem ? stack.getOrDefault(ModDataComponents.TIER, 0) : -1;
    }

    /** The worn backpack's live storage (null when none), with its wrapper made first if the mod has not yet. */
    public static ItemStackHandler storage(Player player) {
        BackpackAttachment attachment = AttachmentUtils.getAttachment(player).orElse(null);
        if (attachment == null || !attachment.hasBackpack()) return null;
        if (attachment.getWrapper() == null) attachment.equipBackpack(attachment.getBackpack(), player);
        BackpackWrapper wrapper = attachment.getWrapper();
        return wrapper == null ? null : wrapper.getStorage();
    }

    private static List<Container> containers(ServerPlayer player) {
        ItemStackHandler storage = storage(player);
        return storage == null ? List.of() : List.of(storage);
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
            boolean extended = slots > 81;
            int rows = (int) Math.ceil((double) slots / (extended ? 11 : 9)) + (extended ? 2 : 0);
            CompoundTag tag = stack.get(ModDataComponents.RENDER_INFO).compoundTag().copy();
            RenderInfo info = new RenderInfo(tag);
            info.updateCapacity(tier.getTankCapacityPerRow() * rows);
            stack.set(ModDataComponents.RENDER_INFO, info);
        }
    }

    /** A new backpack of the given tier. */
    public static ItemStack newBackpack(int ordinal) {
        ItemStack stack = new ItemStack(ModItems.STANDARD_TRAVELERS_BACKPACK);
        applyTier(stack, ordinal);
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

    /**
     * Brings the player in line with the rules: a backpack item in the inventory goes back on their back (or is
     * unpacked if they already wear one), a member of a company with the backpack gets one, a backpack below the
     * company's tier is raised, and the old SIFTEC backpack rows are emptied into it.
     */
    public static void sync(ServerPlayer player) {
        if (player.isRemoved() || Companies.isFake(player)) return;
        BackpackAttachment attachment = AttachmentUtils.getAttachment(player).orElse(null);
        if (attachment == null) return;
        int want = tierFor(player);
        // a backpack item where it should not be: in the inventory or on the cursor
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
        if (!attachment.hasBackpack() && want >= 0) {
            equip(player, attachment, newBackpack(want));
            player.sendSystemMessage(Component.translatable("siftec.backpack.given", Tiers.of(want).getLocalizedName()).withStyle(ChatFormatting.GOLD));
            Siftec.LOGGER.info("SIFTEC: gave {} a Traveler's Backpack, tier {}", player.getGameProfile().name(), want);
        }
        if (attachment.hasBackpack() && attachment.getWrapper() == null) attachment.equipBackpack(attachment.getBackpack(), player);
        int tier = wornTier(player);
        if (tier >= 0 && want > tier) {
            ItemStack raised = attachment.getBackpack().copy();
            applyTier(raised, want);
            equip(player, attachment, raised);
            player.sendSystemMessage(Component.translatable("siftec.backpack.tier", Tiers.of(want).getLocalizedName()).withStyle(ChatFormatting.GOLD));
            Siftec.LOGGER.info("SIFTEC: raised {}'s Traveler's Backpack from tier {} to {}", player.getGameProfile().name(), tier, want);
        }
        Backpack.migrate(player, stack -> insert(player, stack));
    }

    /** A backpack item found off the player's back: worn again if they have none on, else emptied into the inventory. */
    private static void stray(ServerPlayer player, BackpackAttachment attachment, ItemStack stack, int want) {
        if (!attachment.hasBackpack() && want >= 0) {
            int tier = stack.getOrDefault(ModDataComponents.TIER, 0);
            if (tier < want) applyTier(stack, want);
            equip(player, attachment, stack);
            player.sendSystemMessage(Component.translatable("siftec.backpack.returned").withStyle(ChatFormatting.GOLD));
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
                player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
                n++;
            }
        }
        Siftec.LOGGER.info("SIFTEC: unpacked an extra Traveler's Backpack of {} ({} stacks)", player.getGameProfile().name(), n);
        return n;
    }

    /** May this player put this upgrade into their backpack: only once their company has unlocked it. */
    public static boolean mayUseUpgrade(Player player, ItemStack stack) {
        return Locks.allowed(player, stack);
    }

    /** The message when the mod would take the backpack off the player's back. */
    public static void refuseUnequip(Player player) {
        if (player instanceof ServerPlayer server) {
            server.sendOverlayMessage(Component.translatable("siftec.backpack.bound").withStyle(ChatFormatting.GOLD));
        }
    }
}
