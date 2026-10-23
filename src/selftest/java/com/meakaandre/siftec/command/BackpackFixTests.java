package com.meakaandre.siftec.command;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.backpack.Backpack;
import com.meakaandre.siftec.blueprint.Blueprints;
import com.meakaandre.siftec.building.BuildGun;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.BuildingTemplate;
import com.meakaandre.siftec.building.BuildingTests;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.Placement;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.equip.Equipment;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.prefab.Prefabs;
import com.meakaandre.siftec.registry.ModItems;
import com.meakaandre.siftec.vehicle.VehicleEntity;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * {@code /siftec selftest backpackfix}: whatever is paid for from what a player carries pays from the worn backpack as
 * well as the inventory (milestones and the Workshop already did): the Build Gun's buildings, vehicles and the HUB,
 * blueprints, jetpack fuel and filters. A fake player carries the cost only in its backpack: a stand-in container put
 * where {@link Backpack#worn} looks, which is what the Traveler's Backpack compat fills in for real players. The
 * crafting grid's locks and the backpack window's Cloud button need a real player: {@code /siftec selftest tb fixes2}.
 * Every check prints "SELFTEST backpackfix"; failures say FAIL; the last line counts them.
 */
public final class BackpackFixTests {
    private static final int X = 8400, Y = 200, Z = -8400;
    private static final String COMPANY = "selftest_backpackfix";

    private record Later(long at, Runnable run) {
    }

    private static final List<Later> LATER = new ArrayList<>();
    private static int passed, failed;

    private BackpackFixTests() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = server.overworld().getGameTime();
            for (Later l : new ArrayList<>(LATER)) {
                if (l.at > now) continue;
                LATER.remove(l);
                l.run.run();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LATER.clear());
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("backpackfix").executes(BackpackFixTests::run)))));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static void report(CommandSourceStack source, String text) {
        String line = "SELFTEST backpackfix " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        report(source, (ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    private static Company company(ServerLevel level, String... done) {
        CompanyData data = CompanyData.get(level.getServer());
        Company c = new Company();
        c.id = COMPANY;
        c.name = COMPANY;
        c.done.addAll(List.of(done));
        data.companies().put(COMPANY, c);
        data.setDirty();
        return c;
    }

    /** The costs, each paid in its own item (a tag in the item it shows), put into the container. */
    private static void fill(Container bag, List<Milestone.Cost> costs) {
        for (Milestone.Cost cost : costs) {
            if (!cost.present()) continue;
            ItemStack icon = cost.isTag() ? new ItemStack(Items.STONE) : cost.icon();
            int left = cost.count();
            for (int i = 0; i < bag.getContainerSize() && left > 0; i++) {
                if (!bag.getItem(i).isEmpty()) continue;
                int n = Math.min(left, icon.getMaxStackSize());
                bag.setItem(i, icon.copyWithCount(n));
                left -= n;
            }
        }
    }

    private static int items(Container bag) {
        int n = 0;
        for (int i = 0; i < bag.getContainerSize(); i++) n += bag.getItem(i).getCount();
        return n;
    }

    private static void empty(Container bag) {
        for (int i = 0; i < bag.getContainerSize(); i++) bag.setItem(i, ItemStack.EMPTY);
    }

    // ------------------------------------------------------------------------------------------------ the test

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        passed = failed = 0;
        BuildingTests.platform(level, X - 8, Z - 8, X + 48, Z + 48, Y, 16);
        for (VehicleEntity v : level.getEntitiesOfClass(VehicleEntity.class, new AABB(X - 8, Y - 40, Z - 8, X + 48, Y + 40, Z + 48))) v.discard();
        // long enough for the forced chunks' entities to load, so the Tractor can be found once it is put down
        LATER.add(new Later(level.getGameTime() + 100, () -> checks(source, level)));
        report(source, "test site ready; the checks report in a moment");
        return 1;
    }

    private static void checks(CommandSourceStack source, ServerLevel level) {
        // a new fake player each run: what a filter or the jetpack has left from an earlier run is not carried over
        FakePlayer fake = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), "selftest_backpackfix"));
        fake.setGameMode(GameType.SURVIVAL);
        fake.getInventory().clearContent();
        fake.setPos(X - 4.5, Y, Z + 10.5);
        SimpleContainer bag = new SimpleContainer(27);
        Function<ServerPlayer, List<Container>> before = Backpack.worn;
        Backpack.worn = player -> player == fake ? List.of(bag) : before.apply(player);
        try {
            Company company = company(level, "hub_upgrade_2", "vehicular_transport");
            run(source, "build gun", () -> building(source, level, fake, company, bag));
            run(source, "vehicle", () -> vehicle(source, level, fake, company, bag));
            run(source, "hub", () -> hub(source, fake, bag));
            run(source, "blueprint", () -> blueprint(source, level, fake, bag));
            run(source, "jetpack", () -> jetpack(source, fake, bag));
            run(source, "filter", () -> filter(source, fake, bag));
        } finally {
            Backpack.worn = before;
            fake.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            CompanyData.get(level.getServer()).companies().remove(COMPANY);
            CompanyData.get(level.getServer()).setDirty();
        }
        report(source, "done: " + passed + " passed, " + failed + " failed");
    }

    private static void run(CommandSourceStack source, String part, Runnable checks) {
        try {
            checks.run();
        } catch (RuntimeException e) {
            Siftec.LOGGER.error("SIFTEC: selftest backpackfix {} threw", part, e);
            expect(source, part + " runs to the end", false, e.toString());
        }
    }

    /** The Build Gun puts a Smelter down paid only from the backpack. */
    private static void building(CommandSourceStack source, ServerLevel level, FakePlayer fake, Company company, SimpleContainer bag) {
        BuildingDef def = Buildings.def("smelter");
        BuildingTemplate t = def == null ? null : BuildingTemplate.of(def);
        if (t == null) {
            expect(source, "the Smelter has a template", false, "");
            return;
        }
        empty(bag);
        fill(bag, def.cost());
        int had = items(bag);
        Placement p = new Placement(def, new BlockPos(X + 10, Y, Z + 10), Direction.NORTH, false);
        BuildGun.select(fake, "smelter", false, p);
        BuildGun.act(fake, company);
        ControllerBlockEntity controller = BuildGun.controllerAt(level, p.at(t.controller));
        expect(source, "the Build Gun builds with the cost in the backpack, and takes it from there", controller != null && items(bag) == 0,
            "placed " + (controller != null) + ", " + items(bag) + " of " + had + " items left in the backpack");
        if (controller != null) Buildings.dismantle(level, controller);
        BuildGun.select(fake, "", false, null);
    }

    /** The Build Gun puts a Tractor down paid only from the backpack. */
    private static void vehicle(CommandSourceStack source, ServerLevel level, FakePlayer fake, Company company, SimpleContainer bag) {
        BuildingDef def = Buildings.def("tractor");
        if (def == null) {
            expect(source, "the Tractor is listed", false, "");
            return;
        }
        empty(bag);
        fill(bag, def.cost());
        Placement p = new Placement(def, new BlockPos(X + 30, Y, Z + 30), Direction.EAST, false);
        BuildGun.select(fake, "tractor", false, p);
        BuildGun.act(fake, company);
        List<VehicleEntity> made = level.getEntitiesOfClass(VehicleEntity.class, new AABB(p.anchor()).inflate(6));
        expect(source, "the Build Gun puts a vehicle down with the cost in the backpack, and takes it from there", !made.isEmpty() && items(bag) == 0,
            made.size() + " vehicles, " + items(bag) + " items left in the backpack, entity ticking " + level.isPositionEntityTicking(p.anchor()));
        for (VehicleEntity v : made) v.discard();
        BuildGun.select(fake, "", false, null);
    }

    /** The HUB's 20 Raw Iron count when they are in the backpack. */
    private static void hub(CommandSourceStack source, FakePlayer fake, SimpleContainer bag) {
        empty(bag);
        fill(bag, Prefabs.cost(Prefabs.Kind.HUB));
        Milestone.Cost missing = Prefabs.missing(fake, Prefabs.cost(Prefabs.Kind.HUB));
        expect(source, "the HUB's cost counts what is in the backpack", missing == null, missing == null ? "" : "missing " + missing);
        empty(bag);
    }

    /** A blueprint of three cobblestone is built from cobblestone in the backpack. */
    private static void blueprint(CommandSourceStack source, ServerLevel level, FakePlayer fake, SimpleContainer bag) {
        BlockPos designer = new BlockPos(X + 5, Y - 1, Z + 40);
        for (int dx = -1; dx <= 1; dx++) level.setBlock(designer.offset(dx, 1, 0), Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
        ItemStack print = Blueprints.save(level, designer, 1);
        for (int dx = -1; dx <= 1; dx++) level.setBlock(designer.offset(dx, 1, 0), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        if (print.isEmpty()) {
            expect(source, "a blueprint is saved", false, "");
            return;
        }
        empty(bag);
        bag.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
        BlockPos base = new BlockPos(X + 20, Y, Z + 40);
        // the first use shows it, the second on the same spot builds it
        Blueprints.use(fake, print, base);
        Blueprints.use(fake, print, base);
        int built = 0;
        for (int dx = -1; dx <= 1; dx++) if (level.getBlockState(base.offset(dx, 0, 0)).is(Blocks.COBBLESTONE)) built++;
        expect(source, "a blueprint is built from blocks in the backpack, and takes them from there", built == 3 && items(bag) == 0,
            built + " of 3 blocks built, " + items(bag) + " left in the backpack");
        for (int dx = -1; dx <= 1; dx++) level.setBlock(base.offset(dx, 0, 0), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** The jetpack refuels from Compacted Coal in the backpack. */
    private static void jetpack(CommandSourceStack source, FakePlayer fake, SimpleContainer bag) {
        empty(bag);
        bag.setItem(3, new ItemStack(ModItems.PARTS.get("compacted_coal")));
        boolean fuelled = Equipment.refuel(fake);
        expect(source, "the jetpack takes its fuel from the backpack", fuelled && items(bag) == 0, "refuelled " + fuelled + ", " + items(bag) + " left");
    }

    /** The Hazmat Suit takes its Iodine Infused Filter from the backpack. */
    private static void filter(CommandSourceStack source, FakePlayer fake, SimpleContainer bag) {
        empty(bag);
        bag.setItem(5, new ItemStack(ModItems.PARTS.get("iodine_infused_filter")));
        fake.setItemSlot(EquipmentSlot.CHEST, new ItemStack(ModItems.HAZMAT_SUIT.get()));
        boolean shielded = Equipment.shieldsRadiation(fake);
        expect(source, "the Hazmat Suit takes its filter from the backpack", shielded && items(bag) == 0, "shielded " + shielded + ", " + items(bag) + " left");
    }
}
