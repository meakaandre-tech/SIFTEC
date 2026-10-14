package com.meakaandre.siftec.command;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.BuildingTemplate;
import com.meakaandre.siftec.building.BuildingTests;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.Category;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.Paint;
import com.meakaandre.siftec.building.Placement;
import com.meakaandre.siftec.building.PortBlock;
import com.meakaandre.siftec.collect.Collectible;
import com.meakaandre.siftec.collect.CollectibleBlock;
import com.meakaandre.siftec.collect.Collectibles;
import com.meakaandre.siftec.collect.CrashSites;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.MapLock;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.item.ObjectScannerItem;
import com.meakaandre.siftec.node.NodeMap;
import com.meakaandre.siftec.power.PowerTests;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.registry.ModItems;
import com.meakaandre.siftec.sink.Shop;
import com.meakaandre.siftec.sink.Sink;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * /siftec selftest exploration (work package 10): the slugs, Power Shards and overclocking are gone; Mercer Spheres,
 * Somersloops and crash sites come from the seed, are placed in the world, are found by the Object Scanner and start
 * their MAM tree when collected; crash sites ask for nothing, parts or power by distance; the Nutrients start nodes put
 * wild food on the scanner; Field Research's Map; the Sink's values and coupon curve; the Shop and the Customizer's
 * paint; and the four new buildings, with a powered Sink paying for what a belt port brings and a powered Radar Tower
 * sweeping. {@code /siftec worldtest collectibles show x y z} sets one of each collectible out for the camera.
 * Every check prints "SELFTEST exploration"; failures say FAIL.
 */
public final class ExplorationTests {
    private static final int X = 5600, Y = 230, Z = 2400;
    private static final String COMPANY = "selftest_wp10";

    private record Later(long at, Runnable run) {
    }

    private static final List<Later> LATER = new ArrayList<>();
    private static int passed, failed;

    private ExplorationTests() {
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
                .then(Commands.literal("selftest").then(Commands.literal("exploration").executes(ExplorationTests::run)))
                .then(Commands.literal("worldtest").then(Commands.literal("collectibles").then(Commands.literal("show")
                    .then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("y", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer()).executes(ExplorationTests::show)))))))));
    }

    private static void later(ServerLevel level, int ticks, Runnable run) {
        LATER.add(new Later(level.getGameTime() + ticks, run));
    }

    private static void report(CommandSourceStack source, String text) {
        String line = "SELFTEST exploration " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        report(source, (ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    private static boolean registered(String id) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).isPresent();
    }

    private static ItemStack stack(String id, int n) {
        return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)), n);
    }

    private static Company company(ServerLevel level, String... done) {
        CompanyData data = CompanyData.get(level.getServer());
        Company c = data.companies().get(COMPANY);
        if (c == null) {
            c = new Company();
            c.id = COMPANY;
            c.name = "Selftest Exploration";
            data.companies().put(COMPANY, c);
        }
        c.done.addAll(List.of(done));
        Companies.save(level.getServer());
        return c;
    }

    private static Company fresh(String... done) {
        Company c = new Company();
        c.id = "selftest_wp10_fresh";
        c.done.addAll(List.of(done));
        return c;
    }

    /** Where a collectible's block ended up in its column (its pocket, its float or its pod), or null. */
    private static @Nullable BlockPos find(ServerLevel level, Collectibles.Spot spot) {
        Block block = ModBlocks.COLLECTIBLES.get(spot.type()).get();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = level.getMaxY(); y >= level.getMinY(); y--) if (level.getBlockState(p.set(spot.x(), y, spot.z())).is(block)) return p.immutable();
        return null;
    }

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        passed = 0;
        failed = 0;

        // ---- overclocking is gone: no slugs, no Power Shards
        expect(source, "the power slugs and Power Shards are gone", !registered("siftec:power_shard") && !registered("siftec:blue_power_slug")
            && !registered("siftec:yellow_power_slug") && !registered("siftec:purple_power_slug"), "");
        boolean noSlugCollectible = true;
        for (Collectible c : Collectible.values()) noSlugCollectible &= !c.id().contains("slug");
        expect(source, "the collectibles are Mercer Spheres, Somersloops and crash sites only", noSlugCollectible && Collectible.values().length == 3, "");
        boolean noShardTokens = true;
        for (Milestone m : Milestones.all()) for (String t : m.tokens()) noShardTokens &= !t.startsWith("shards") && !t.startsWith("amplifier");
        expect(source, "no milestone or research gives Power Shard slots or the Production Amplifier", noShardTokens, "");

        // ---- from the seed: counted over the cells round spawn
        int[] origin = NodeMap.origin();
        if (origin == null) {
            report(source, "no node map yet: run after the world has started");
            return 0;
        }
        Map<Collectible, Integer> counts = new EnumMap<>(Collectible.class);
        int sloopsNear = 0, crash = 0, crashFreeNear = 0, crashNear = 0, crashPower = 0, crashPowerNear = 0, crashParts = 0;
        int oc = Math.floorDiv(origin[0], Collectibles.CELL), oz = Math.floorDiv(origin[1], Collectibles.CELL);
        boolean same = true;
        for (int dx = -25; dx <= 25; dx++) {
            for (int dz = -25; dz <= 25; dz++) {
                Optional<Collectibles.Spot> s = Collectibles.inCell(level, oc + dx, oz + dz);
                same &= s.equals(Collectibles.inCell(level, oc + dx, oz + dz));
                if (s.isEmpty()) continue;
                Collectibles.Spot spot = s.get();
                counts.merge(spot.type(), 1, Integer::sum);
                double d = Math.sqrt(Math.pow(spot.x() - origin[0], 2) + Math.pow(spot.z() - origin[1], 2));
                if (spot.type() == Collectible.SOMERSLOOP && d < 1000) sloopsNear++;
                if (spot.type() == Collectible.CRASH_SITE) {
                    crash++;
                    CrashSites.Requirement need = CrashSites.requirement(level, new BlockPos(spot.x(), 0, spot.z()));
                    if (d < 400) {
                        crashNear++;
                        if (need.kind() == CrashSites.Requirement.Kind.FREE) crashFreeNear++;
                    }
                    if (need.kind() == CrashSites.Requirement.Kind.POWER) {
                        crashPower++;
                        if (d < 900) crashPowerNear++;
                    }
                    if (need.kind() == CrashSites.Requirement.Kind.PARTS) crashParts++;
                }
            }
        }
        int spheres = counts.getOrDefault(Collectible.MERCER_SPHERE, 0), sloops = counts.getOrDefault(Collectible.SOMERSLOOP, 0);
        expect(source, "from the seed: Mercer Spheres common, Somersloops rare and only far from spawn, crash sites in between",
            same && spheres > crash && crash > sloops && sloops > 0 && sloopsNear == 0, spheres + " spheres, " + sloops + " Somersloops, " + crash + " crash sites in 51x51 cells");
        expect(source, "crash sites: near spawn they open freely; further out some want parts, and past 900 blocks some want power",
            crashFreeNear == crashNear && crashParts > 0 && crashPower > 0 && crashPowerNear == 0,
            crashNear + " near spawn, " + crashParts + " want parts, " + crashPower + " want power");

        // ---- placing, scanning and collecting each kind (a fake player of a fresh company does the collecting)
        FakePlayer fake = FakePlayer.get(level);
        fake.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        fake.getInventory().clearContent();
        Company mine = Companies.of(fake);
        for (Collectible type : Collectible.values()) {
            Optional<Collectibles.Spot> nearest = Collectibles.nearest(level, origin[0], origin[1], type, Set.copyOf(mine.collected), 40);
            if (nearest.isEmpty()) {
                expect(source, type.id() + ": one within 40 cells of spawn", false, "");
                continue;
            }
            Collectibles.Spot spot = nearest.get();
            level.getChunk(spot.x() >> 4, spot.z() >> 4);
            BlockPos at = find(level, spot);
            if (at == null) {
                Collectibles.place(level, spot);
                at = find(level, spot);
            }
            String where = at == null ? "not placed" : at.toShortString();
            if (type == Collectible.MERCER_SPHERE) {
                BlockState patch = at == null ? Blocks.AIR.defaultBlockState() : level.getBlockState(at.below(2));
                boolean slate = patch.is(Blocks.DEEPSLATE) || BuiltInRegistries.BLOCK.getKey(patch.getBlock()).getPath().equals("siftslate");
                expect(source, "Mercer Sphere: floats one block over a siftslate patch", at != null && level.getBlockState(at.below()).isAir() && slate,
                    where + " over " + patch);
            } else if (type == Collectible.SOMERSLOOP) {
                expect(source, "Somersloop: in a pocket deep underground, at the height the seed gives", at != null
                    && at.getY() == Collectibles.deepY(level.getSeed(), spot.x(), spot.z()) && at.getY() < 0, where);
            } else {
                expect(source, "Crash site: the pod stands on the ground", at != null, where);
            }
            if (at == null) continue;
            if (type == Collectible.CRASH_SITE) {
                CrashSites.Requirement need = CrashSites.requirement(level, at);
                if (need.cost() != null) fake.getInventory().add(stack(need.cost().key(), need.cost().count()));
            }
            boolean got = CollectibleBlock.tryCollect(fake, at, type);
            boolean item = fake.getInventory().countItem(BuiltInRegistries.ITEM.getValue(Siftec.id(type.itemId()))) > 0;
            expect(source, type.id() + ": collected once, the item given, and its MAM tree (" + type.tree + ") started",
                got && item && mine.collected.contains(CollectibleBlock.key(at)) && mine.trees.contains(type.tree) && !CollectibleBlock.tryCollect(fake, at, type),
                "collected " + got + ", item " + item + ", trees " + mine.trees);
            Optional<Collectibles.Spot> next = Collectibles.nearest(level, origin[0], origin[1], type, Set.copyOf(mine.collected), 40);
            expect(source, type.id() + ": the scanner skips the one the company collected", next.isEmpty() || !next.get().key().equals(spot.key()), "");
        }
        expect(source, "the Hard Drive page and the Alien Technology tree are open to the company now", mine.trees.containsAll(List.of("alien", "hard_drives")), mine.trees.toString());

        // ---- the Object Scanner: what each company can look for, and wild food in loaded land
        Company nutrients = fresh("mam_nutrients_sweet_berry_research", "mam_nutrients_apple_research", "mam_nutrients_glow_berry_research");
        List<ObjectScannerItem.Target> plain = ObjectScannerItem.open(fresh(), false), fed = ObjectScannerItem.open(nutrients, false);
        expect(source, "Object Scanner: crash sites from the start; the Nutrients start nodes add berry bushes, apple trees and glow berries",
            plain.equals(List.of(ObjectScannerItem.Target.HARD_DRIVE)) && fed.containsAll(List.of(ObjectScannerItem.Target.SWEET_BERRIES,
                ObjectScannerItem.Target.APPLES, ObjectScannerItem.Target.GLOW_BERRIES)), plain + " / " + fed);
        BuildingTests.platform(level, X - 4, Z - 40, X + 4, Z - 30, Y, 6);
        BlockPos bush = new BlockPos(X, Y + 1, Z - 35);
        level.setBlock(bush, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(BlockStateProperties.AGE_3, 3), Block.UPDATE_CLIENTS);
        BlockPos seen = ObjectScannerItem.nearestBlock(level, bush.offset(3, 0, 3), ObjectScannerItem.Target.SWEET_BERRIES.plant(), 2);
        expect(source, "Object Scanner: finds a ripe sweet berry bush", bush.equals(seen), seen == null ? "nothing" : seen.toShortString());

        // ---- Field Research's Map
        expect(source, "Field Research gives the Map: Xaero's Minimap is off until it is done", !MapLock.open(fresh()) && MapLock.open(fresh("field_research")), "");

        // ---- the Sink's values and the coupon curve
        long sheet = Sink.worthOne(new ItemStack(Items.IRON_INGOT)), dirt = Sink.worthOne(new ItemStack(Items.DIRT));
        long plate = Sink.worthOne(stack("create:iron_sheet", 1)), drive = Sink.worthOne(stack("siftec:hard_drive", 1));
        ItemStack book = new ItemStack(Items.DIAMOND_SWORD);
        long sword = Sink.worthOne(book);
        expect(source, "Sink values: Satisfactory's for its parts (Iron Plate 6, a spare Hard Drive 1,000), the tab's for vanilla (dirt 1, iron ingot 2)",
            plate == 6 && drive == 1000 && dirt == 1 && sheet == 2 && sword == 2 * 200 + 1, "plate " + plate + ", drive " + drive + ", dirt " + dirt + ", ingot " + sheet + ", sword " + sword);
        expect(source, "the Sink takes anything but the waste and coupons", Sink.accepts(new ItemStack(Items.ROTTEN_FLESH)) && !Sink.accepts(stack("siftec:toxic_residue", 1))
            && !Sink.accepts(new ItemStack(ModItems.FICSIT_COUPON.get())), "");
        expect(source, "coupons on Satisfactory's curve: 500, 1,250, 2,000, 3,250, then 249,501,250 past 3,000",
            Sink.couponCost(1) == 500 && Sink.couponCost(3) == 500 && Sink.couponCost(4) == 1250 && Sink.couponCost(7) == 2000 && Sink.couponCost(10) == 3250
                && Sink.couponCost(3001) == 249501250L, Sink.couponCost(4) + ", " + Sink.couponCost(10));
        Company printer = fresh();
        printer.points = 1500 + 1249;
        int printed = Sink.print(printer);
        expect(source, "printing: 2,749 points make three coupons, 1,249 points left over", printed == 3 && printer.points == 1249 && printer.couponsPrinted == 3,
            printed + " printed, " + printer.points + " left");

        // ---- the Shop and the Customizer
        Shop.Entry customizer = Shop.entry("customizer"), swatch = Shop.entry("swatch"), copper = Shop.entry("finish_copper");
        Company buyer = fresh();
        if (swatch != null) Shop.unlock(buyer, swatch);
        if (copper != null) Shop.unlock(buyer, copper);
        expect(source, "Shop: cosmetics only; the Customizer is free; a swatch slot adds a swatch; a finish unlocks for the company",
            Shop.entries().size() >= 30 && customizer != null && customizer.owned(fresh()) && buyer.swatches.size() == 2 && Shop.hasFinish(buyer, "copper")
                && !Shop.hasFinish(fresh(), "copper") && Shop.hasFinish(fresh(), "painted"), Shop.entries().size() + " entries");
        boolean onlyCosmetic = true;
        for (Shop.Entry e : Shop.entries()) onlyCosmetic &= Set.of("customizer", "swatch", "finish", "skin", "decor", "cup").contains(e.kind());
        expect(source, "Shop: nothing but cosmetics (no parts, equipment or progression)", onlyCosmetic, "");
        Paint paint = new Paint("blue", "red", "painted", "brass");
        expect(source, "Customizer paint: orange panels take the primary colour, dark grey the secondary, light grey the casing skin; a finish changes the material",
            paint.look(Blocks.ORANGE_CONCRETE.defaultBlockState(), false).is(Blocks.BLUE_CONCRETE)
                && paint.look(block("create:industrial_iron_block").defaultBlockState(), false).is(Blocks.RED_CONCRETE)
                && paint.look(Blocks.IRON_BLOCK.defaultBlockState(), false).is(block("create:brass_casing"))
                && paint.withFinish("copper").look(Blocks.ORANGE_CONCRETE.defaultBlockState(), false).is(Blocks.WAXED_COPPER_BLOCK)
                && Paint.NONE.look(Blocks.ORANGE_CONCRETE.defaultBlockState(), false).is(Blocks.ORANGE_CONCRETE)
                && Paint.parse(paint.write()).equals(paint), "");
        expect(source, "decorations and cups exist for every Shop entry", ModBlocks.DECOR.size() >= 20 && ModItems.CUPS.size() == 2, ModBlocks.DECOR.size() + " decorations");

        // ---- the Equipment Workshop's list: equipment only, the Xeno-Zapper, Rebar Gun and Xeno-Basher among it
        Set<String> workshop = new java.util.HashSet<>();
        for (Milestones.Build b : Milestones.workshop()) workshop.add(b.item().toString());
        expect(source, "Equipment Workshop: builds the Xeno-Zapper, the Rebar Gun and the Xeno-Basher, and no longer the Sink, Shop or Radar Tower",
            workshop.containsAll(List.of("siftec:xeno_zapper", "cgs:nailgun", "cgs:hammer", "siftec:object_scanner", "siftec:jetpack"))
                && !workshop.contains("siftec:awesome_sink") && !workshop.contains("siftec:radar_tower") && !registered("siftec:awesome_sink"), "");

        // ---- the four buildings
        boolean defs = true;
        for (String id : List.of("equipment_workshop", "awesome_sink", "awesome_shop", "radar_tower")) {
            BuildingDef def = Buildings.def(id);
            defs &= def != null && def.category() == Category.SPECIAL && BuildingTemplate.of(def) != null;
        }
        BuildingDef sinkDef = Buildings.def("awesome_sink"), radarDef = Buildings.def("radar_tower");
        expect(source, "the Equipment Workshop, AWESOME Sink and Shop and Radar Tower are Build Gun buildings in the Special tab; the Sink and Radar draw 30 MW",
            defs && sinkDef != null && sinkDef.su() == Math.round(30 * 204.8) && radarDef != null && radarDef.su() == Math.round(30 * 204.8)
                && "resource_sink_bonus_program".equals(sinkDef.unlock()), "");
        world(source, level);
        return 1;
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
    }

    /** The Sink, Shop, Workshop and Radar Tower put down on their own Alien Power Augmenter, and run. */
    private static void world(CommandSourceStack source, ServerLevel level) {
        Company company = company(level, "resource_sink_bonus_program");
        BuildingTests.platform(level, X - 30, Z - 20, X + 40, Z + 30, Y, 24);
        ControllerBlockEntity augmenter = place(source, level, company, "alien_power_augmenter", new BlockPos(X, Y + 1, Z));
        ControllerBlockEntity sink = place(source, level, company, "awesome_sink", new BlockPos(X + 15, Y + 1, Z));
        ControllerBlockEntity radar = place(source, level, company, "radar_tower", new BlockPos(X + 25, Y + 1, Z));
        ControllerBlockEntity shop = place(source, level, company, "awesome_shop", new BlockPos(X - 15, Y + 1, Z));
        ControllerBlockEntity workshop = place(source, level, company, "equipment_workshop", new BlockPos(X - 22, Y + 1, Z));
        expect(source, "all four buildings go down", sink != null && radar != null && shop != null && workshop != null, "");
        boolean wired = PowerTests.wire(PowerTests.connector(level, augmenter), PowerTests.connector(level, sink))
            & PowerTests.wire(PowerTests.connector(level, augmenter), PowerTests.connector(level, radar));
        if (shop != null) Buildings.repaint(level, shop, new Paint("lime", "black", "painted", ""));
        long before = company.points;
        later(level, 60, () -> {
            ControllerBlockEntity s = controller(level, sink), r = controller(level, radar);
            expect(source, "the Sink and the Radar Tower run on the Augmenter's power", wired && s != null && s.powered && r != null && r.powered,
                "wired " + wired + ", sink " + (s != null && s.powered) + ", radar " + (r != null && r.powered));
            // ten Iron Sheets in at the Sink's input port, as a belt would put them
            if (s != null && s.placement() != null) {
                BlockPos port = s.placement().at(0, 0, 4);
                BlockState ps = level.getBlockState(port);
                var storage = ItemStorage.SIDED.find(level, port, ps.hasProperty(PortBlock.FACING) ? ps.getValue(PortBlock.FACING) : null);
                if (storage != null) {
                    try (Transaction tx = Transaction.openOuter()) {
                        storage.insert(ItemVariant.of(BuiltInRegistries.ITEM.getValue(Identifier.parse("create:iron_sheet"))), 10, tx);
                        tx.commit();
                    }
                }
            }
            later(level, 40, () -> {
                ControllerBlockEntity s2 = controller(level, sink), r2 = controller(level, radar), sh = controller(level, shop);
                expect(source, "the Sink destroys what its input port takes and pays the company: 10 Iron Sheets for 60 points",
                    company.points - before == 60 && s2 != null && s2.made >= 10, (company.points - before) + " points");
                expect(source, "the powered Radar Tower has swept its range", r2 != null && r2.lastSweep > 0, "");
                boolean painted = sh != null && sh.placement() != null && level.getBlockState(sh.placement().at(0, 4, 0)).is(Blocks.LIME_CONCRETE);
                expect(source, "the Customizer's paint holds: the Shop's orange roof is lime and the building still stands", painted && sh.paint.equals("lime/black/painted/"),
                    sh == null ? "no Shop" : sh.paint + ", roof " + (sh.placement() == null ? "-" : level.getBlockState(sh.placement().at(0, 4, 0)).toString()));
                report(source, "result: " + passed + " passed, " + failed + " failed");
            });
        });
    }

    private static @Nullable ControllerBlockEntity place(CommandSourceStack source, ServerLevel level, Company company, String id, BlockPos anchor) {
        BuildingDef def = Buildings.def(id);
        if (def == null) return null;
        Placement p = new Placement(def, anchor, Direction.NORTH, false);
        Buildings.Check check = Buildings.place(level, p, company, null, true);
        if (!check.ok()) {
            report(source, "could not place " + id + ": " + check.message().getString());
            return null;
        }
        BuildingTemplate t = BuildingTemplate.of(def);
        return t != null && level.getBlockEntity(p.at(t.controller)) instanceof ControllerBlockEntity c ? c : null;
    }

    private static @Nullable ControllerBlockEntity controller(ServerLevel level, @Nullable ControllerBlockEntity was) {
        return was != null && level.getBlockEntity(was.getBlockPos()) instanceof ControllerBlockEntity c ? c : null;
    }

    /** One of each collectible on a grass platform, for the camera: a Mercer Sphere over its siftslate, a Somersloop, a pod. */
    private static int show(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getServer().overworld();
        int x = IntegerArgumentType.getInteger(context, "x"), y = IntegerArgumentType.getInteger(context, "y"), z = IntegerArgumentType.getInteger(context, "z");
        BlockState slate = BuiltInRegistries.BLOCK.getOptional(Identifier.fromNamespaceAndPath("the_sift", "siftslate")).orElse(Blocks.DEEPSLATE).defaultBlockState();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (dx * dx + dz * dz < 2) level.setBlockAndUpdate(new BlockPos(x + dx, y - 1, z + dz), slate);
        level.setBlockAndUpdate(new BlockPos(x, y + 1, z), ModBlocks.COLLECTIBLES.get(Collectible.MERCER_SPHERE).get().defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(x + 2, y, z), ModBlocks.COLLECTIBLES.get(Collectible.SOMERSLOOP).get().defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(x + 4, y, z), ModBlocks.COLLECTIBLES.get(Collectible.CRASH_SITE).get().defaultBlockState());
        CrashSites.wreckage(level, new BlockPos(x + 4, y, z));
        context.getSource().sendSuccess(() -> Component.literal("collectibles set out at " + x + " " + y + " " + z), false);
        return 1;
    }
}
