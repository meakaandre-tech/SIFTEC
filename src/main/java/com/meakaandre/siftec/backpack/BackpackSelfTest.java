package com.meakaandre.siftec.backpack;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;

/**
 * For the client test ({@code /siftec selftest bp ...}): sets how many backpack slots are open, fills the inventory
 * and drops items to be picked up, opens a test chest, and checks that every item is still there exactly once.
 * <ul>
 * <li>{@code slots <n>}: the player's company has exactly n/3 backpack rewards;</li>
 * <li>{@code fill}: hotbar and main rows full of dirt, backpack and test chest emptied, 128 cobblestone and a
 * diamond dropped on the player to be picked up (into the backpack);</li>
 * <li>{@code chest}: opens the test chest (16 iron ingots in its first slot the first time);</li>
 * <li>{@code check}: counts every test item in the inventory, backpack, test chest, cursor and on the ground;</li>
 * <li>{@code drop}: puts items in the backpack and runs the death drop, then picks the items up again by hand.</li>
 * </ul>
 */
public final class BackpackSelfTest {
    private static final SimpleContainer CHEST = new SimpleContainer(27);
    private static boolean chestFilled;
    private static final Map<Item, Integer> EXPECTED = Map.of(Items.DIRT, 36 * 64, Items.COBBLESTONE, 128, Items.DIAMOND, 1, Items.IRON_INGOT, 16);

    private BackpackSelfTest() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("bp")
                    .then(Commands.literal("slots").then(Commands.argument("n", IntegerArgumentType.integer(0, Backpack.SIZE))
                        .executes(context -> slots(context.getSource(), IntegerArgumentType.getInteger(context, "n")))))
                    .then(Commands.literal("fill").executes(context -> fill(context.getSource())))
                    .then(Commands.literal("chest").executes(context -> chest(context.getSource())))
                    .then(Commands.literal("check").executes(context -> check(context.getSource())))
                    .then(Commands.literal("drop").executes(context -> drop(context.getSource())))))
        ));
    }

    private static List<ServerPlayer> players(CommandSourceStack source) {
        return source.getServer().getPlayerList().getPlayers();
    }

    private static int slots(CommandSourceStack source, int n) {
        for (ServerPlayer player : players(source)) {
            Company company = Companies.of(player);
            List<Milestone> rewards = new java.util.ArrayList<>();
            for (Milestone m : Milestones.all()) if (m.tokens().contains("backpack")) rewards.add(m);
            for (Milestone m : rewards) company.done.remove(m.id());
            for (int i = 0; i < Math.min(rewards.size(), n / Backpack.PER_REWARD); i++) company.done.add(rewards.get(i).id());
            Companies.save(source.getServer());
            com.meakaandre.siftec.tweak.SpeedCap.recompute(source.getServer());
            report(source, "SELFTEST bp slots: " + Backpack.unlocked(player) + " open for " + player.getGameProfile().name()
                + " (asked " + n + ", rewards in the game " + rewards.size() + ")");
        }
        return 1;
    }

    private static int fill(CommandSourceStack source) {
        for (ServerPlayer player : players(source)) {
            Inventory inventory = player.getInventory();
            for (int i = 0; i < 36; i++) inventory.setItem(i, new ItemStack(Items.DIRT, 64));
            player.getAttachedOrCreate(Backpack.CONTENTS).items.clear();
            CHEST.clearContent();
            chestFilled = false;
            player.containerMenu.setCarried(ItemStack.EMPTY);
            for (ItemStack stack : List.of(new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.DIAMOND))) {
                ItemEntity entity = new ItemEntity(player.level(), player.getX(), player.getY() + 0.5, player.getZ(), stack, 0, 0, 0);
                entity.setNoPickUpDelay();
                player.level().addFreshEntity(entity);
            }
            player.inventoryMenu.broadcastChanges();
            report(source, "SELFTEST bp fill: inventory full of dirt, 2x64 cobblestone and a diamond dropped on " + player.getGameProfile().name());
        }
        return 1;
    }

    private static int chest(CommandSourceStack source) {
        if (!chestFilled) {
            CHEST.clearContent();
            CHEST.setItem(0, new ItemStack(Items.IRON_INGOT, 16));
            chestFilled = true;
        }
        for (ServerPlayer player : players(source)) {
            player.openMenu(new SimpleMenuProvider((id, inventory, p) -> ChestMenu.threeRows(id, inventory, CHEST), Component.literal("Backpack test chest")));
        }
        return 1;
    }

    private static int count(Iterable<ItemStack> stacks, Item item) {
        int n = 0;
        for (ItemStack stack : stacks) if (stack.is(item)) n += stack.getCount();
        return n;
    }

    private static int check(CommandSourceStack source) {
        for (ServerPlayer player : players(source)) {
            List<ItemStack> inventory = new java.util.ArrayList<>();
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) inventory.add(player.getInventory().getItem(i));
            List<ItemStack> backpack = player.getAttachedOrCreate(Backpack.CONTENTS).items;
            List<ItemStack> chest = new java.util.ArrayList<>();
            for (int i = 0; i < CHEST.getContainerSize(); i++) chest.add(CHEST.getItem(i));
            List<ItemStack> ground = player.level().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(24)).stream().map(ItemEntity::getItem).toList();
            StringBuilder out = new StringBuilder("SELFTEST bp check: ");
            boolean conserved = true;
            for (Item item : List.of(Items.DIRT, Items.COBBLESTONE, Items.DIAMOND, Items.IRON_INGOT)) {
                int inv = count(inventory, item), bp = count(backpack, item), ch = count(chest, item), gr = count(ground, item);
                int carried = player.containerMenu.getCarried().is(item) ? player.containerMenu.getCarried().getCount() : 0;
                int total = inv + bp + ch + carried;
                if (item != Items.IRON_INGOT || chestFilled) conserved &= total == EXPECTED.get(item) && gr == 0;
                out.append(item.toString().replace("minecraft:", "")).append(" inv ").append(inv).append(" bp ").append(bp)
                    .append(" chest ").append(ch).append(" cursor ").append(carried).append(" ground ").append(gr).append("; ");
            }
            out.append("backpack slots 0-3: ");
            for (int i = 0; i < 4; i++) out.append(backpack.get(i)).append(i < 3 ? ", " : "; ");
            out.append("hotbar 0: ").append(player.getInventory().getItem(0)).append("; all there once, nothing on the ground: ").append(conserved);
            report(source, out.toString());
        }
        return 1;
    }

    private static int drop(CommandSourceStack source) {
        for (ServerPlayer player : players(source)) {
            Inventory inventory = player.getInventory();
            inventory.clearContent();
            List<ItemStack> items = player.getAttachedOrCreate(Backpack.CONTENTS).items;
            items.clear();
            items.set(0, new ItemStack(Items.GOLD_INGOT, 7));
            items.set(5, new ItemStack(Items.EMERALD, 3));
            inventory.setItem(0, new ItemStack(Items.STICK, 2));
            int before = player.level().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(24)).size();
            inventory.dropAll();
            List<ItemEntity> dropped = player.level().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(24));
            int gold = 0, emerald = 0, stick = 0;
            for (ItemEntity entity : dropped) {
                if (entity.getItem().is(Items.GOLD_INGOT)) gold += entity.getItem().getCount();
                if (entity.getItem().is(Items.EMERALD)) emerald += entity.getItem().getCount();
                if (entity.getItem().is(Items.STICK)) stick += entity.getItem().getCount();
            }
            boolean empty = items.stream().allMatch(ItemStack::isEmpty);
            for (ItemEntity entity : dropped) entity.discard();
            report(source, "SELFTEST bp drop: death drop takes the backpack along: backpack empty " + empty + ", dropped gold " + gold
                + " emerald " + emerald + " stick " + stick + " (entities before " + before + ") ok " + (empty && gold == 7 && emerald == 3 && stick == 2));
        }
        return 1;
    }

    private static void report(CommandSourceStack source, String text) {
        Siftec.LOGGER.info(text);
        source.sendSuccess(() -> Component.literal(text), false);
    }
}
