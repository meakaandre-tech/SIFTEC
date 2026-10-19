package com.meakaandre.siftec.command;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.BuildingIndex;
import com.meakaandre.siftec.building.BuildingTemplate;
import com.meakaandre.siftec.building.BuildingTests;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.Category;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.Placement;
import com.meakaandre.siftec.building.PortBlock;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.drone.DroneEntity;
import com.meakaandre.siftec.drone.DronePortBlockEntity;
import com.meakaandre.siftec.drone.PortContainer;
import com.meakaandre.siftec.food.PreserveItem;
import com.meakaandre.siftec.geyser.GeyserSearch;
import com.meakaandre.siftec.hub.Locks;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.item.ObjectScannerItem;
import com.meakaandre.siftec.map.MapGrid;
import com.meakaandre.siftec.map.MapPoi;
import com.meakaandre.siftec.map.MapTerrain;
import com.meakaandre.siftec.place.Places;
import com.meakaandre.siftec.power.PowerTests;
import com.meakaandre.siftec.prefab.HubBurnerBlockEntity;
import com.meakaandre.siftec.prefab.Prefabs;
import com.meakaandre.siftec.prefab.Templates;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.registry.ModItems;
import com.meakaandre.siftec.vehicle.VehicleEntity;
import com.meakaandre.siftec.vehicle.VehicleInputPayload;
import com.meakaandre.siftec.vehicle.VehicleKind;
import com.meakaandre.siftec.vehicle.Vehicles;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.content.fluids.tank.FluidTankBlockEntity;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import com.zurrtum.create.content.logistics.box.PackageItem;
import com.zurrtum.create.content.processing.burner.BlazeBurnerBlockEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /siftec selftest polish}: the polish pass, on a test site of its own (X 14000, Z 14000). Every check prints
 * "SELFTEST polish"; failures say FAIL; the last line counts them.
 * <ul>
 * <li>2: geysers on the Object Scanner (Geothermal Power) and found in land nobody has loaded (the Radar Tower's search);</li>
 * <li>3: a company's Fluid Tanks stay one block wide until Petroleum Power;</li>
 * <li>4: the Nutrients tokens hold back pickles, jams, vinegar, cider and mead, and throwing a jar;</li>
 * <li>5: vehicles are picked and blocked along their whole length; the Explorer burns 90 MW;</li>
 * <li>6: SAM Reanimation says it is a Constructor recipe;</li>
 * <li>7: the Drone Port building: its terminal, ports, pad and drone, a trip pad to pad;</li>
 * <li>8: chutes reach a building's machines only from inside it;</li>
 * <li>9: (gone: the old single-block machines were removed in review fixes 6);</li>
 * <li>10: the HUB's Biomass Burners run a real boiler, engine and coil.</li>
 * </ul>
 */
public final class PolishTests {
    private static final int X = 14000, Y = 200, Z = 14000;
    private static final String COMPANY = "selftest_polish", RICH = "selftest_polish_rich";

    private record Later(long at, Runnable run) {
    }

    private static final List<Later> LATER = new ArrayList<>();
    private static int passed, failed, pending;

    private PolishTests() {
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
                .then(Commands.literal("selftest").then(Commands.literal("polish").executes(PolishTests::run)))));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static void later(ServerLevel level, int ticks, Runnable run) {
        pending++;
        LATER.add(new Later(level.getServer().overworld().getGameTime() + ticks, () -> {
            try {
                run.run();
            } catch (RuntimeException e) {
                Siftec.LOGGER.error("SIFTEC: selftest polish check threw", e);
                failed++;
                Siftec.LOGGER.info("SELFTEST polish FAIL a delayed check threw: {}", e.toString());
            }
            pending--;
        }));
    }

    static void report(CommandSourceStack source, String text) {
        String line = "SELFTEST polish " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        report(source, (ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    private static Company company(ServerLevel level, String id, String... done) {
        CompanyData data = CompanyData.get(level.getServer());
        Company c = data.companies().get(id);
        if (c == null) {
            c = new Company();
            c.id = id;
            c.name = id;
            data.companies().put(id, c);
        }
        c.done.addAll(List.of(done));
        data.setDirty();
        return c;
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
    }

    private static @Nullable ControllerBlockEntity place(CommandSourceStack source, ServerLevel level, @Nullable Company company, String id, BlockPos anchor,
                                                         Direction facing) {
        BuildingDef def = Buildings.def(id);
        BuildingTemplate t = def == null ? null : BuildingTemplate.of(def);
        if (t == null) {
            expect(source, id + " exists", false, "");
            return null;
        }
        Placement p = new Placement(def, anchor, facing, false);
        Buildings.Check check = Buildings.place(level, p, company, null, true);
        ControllerBlockEntity c = level.getBlockEntity(p.at(t.controller)) instanceof ControllerBlockEntity ce ? ce : null;
        if (!check.ok() || c == null) expect(source, id + " goes down at " + anchor.toShortString(), false, check.message().getString());
        return c;
    }

    private static void run(CommandSourceStack source, String part, Runnable checks) {
        try {
            checks.run();
        } catch (RuntimeException e) {
            Siftec.LOGGER.error("SIFTEC: selftest polish {} threw", part, e);
            expect(source, part + " runs to the end", false, e.toString());
        }
    }

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        passed = failed = pending = 0;
        BuildingTemplate.forget();
        BuildingTests.platform(level, X - 10, Z - 10, X + 230, Z + 150, Y, 30);
        later(level, 10, () -> {
            run(source, "3 tanks", () -> tanks(source, level));
            run(source, "4 nutrients", () -> nutrients(source, level));
            run(source, "5 vehicles", () -> vehicles(source, level));
            run(source, "6 SAM text", () -> samText(source));
            run(source, "7 drone port", () -> dronePort(source, level));
            run(source, "8 chutes", () -> chutes(source, level));
            run(source, "10 HUB burners", () -> hubBurners(source, level));
            run(source, "2 geysers", () -> geysers(source, level));
            run(source, "1 shop", () -> ShopTests.run(source, level, X + 120, Y, Z + 100));
            finish(source, level);
        });
        report(source, "test site ready; the checks report over the next minute");
        return 1;
    }

    private static void finish(CommandSourceStack source, ServerLevel level) {
        if (pending > 0 || GeyserSearch.busy()) {
            LATER.add(new Later(level.getServer().overworld().getGameTime() + 10, () -> finish(source, level)));
            return;
        }
        report(source, "done: " + passed + " passed, " + failed + " failed");
    }

    // ------------------------------------------------------------------------------------------------ 3: tanks

    private static void tanks(CommandSourceStack source, ServerLevel level) {
        Company plain = company(level, COMPANY);
        Company rich = company(level, RICH, "petroleum_power");
        BlockState tank = block("create:fluid_tank").defaultBlockState();
        String[] owners = {plain.id, rich.id};
        BlockPos[] at = {new BlockPos(X, Y, Z), new BlockPos(X + 6, Y, Z)};
        for (int k = 0; k < 2; k++) {
            for (int dx = 0; dx < 2; dx++) {
                for (int dz = 0; dz < 2; dz++) {
                    BlockPos pos = at[k].offset(dx, 0, dz);
                    level.setBlock(pos, tank, Block.UPDATE_ALL);
                    if (level.getBlockEntity(pos) != null) level.getBlockEntity(pos).setAttached(com.meakaandre.siftec.owner.Ownership.OWNER, owners[k]);
                }
            }
        }
        later(level, 20, () -> {
            int[] width = new int[2];
            for (int k = 0; k < 2; k++) {
                if (level.getBlockEntity(at[k]) instanceof FluidTankBlockEntity t && t.getControllerBE() != null) width[k] = t.getControllerBE().getWidth();
            }
            expect(source, "3: before Petroleum Power a company's Fluid Tanks stay one block wide; after it they join up to Create's size",
                width[0] == 1 && width[1] == 2, "without " + width[0] + ", with " + width[1]);
            expect(source, "3: Petroleum Power carries the larger tanks", Milestones.get("petroleum_power") != null
                && Milestones.get("petroleum_power").tokens().contains(com.meakaandre.siftec.tweak.TankSizes.TOKEN), "");
        });
    }

    // ------------------------------------------------------------------------------------------------ 4: nutrients

    private static void nutrients(CommandSourceStack source, ServerLevel level) {
        String preserved = "mam_nutrients_preserved_foods", fermenting = "mam_nutrients_bulk_fermentation", brewing = "mam_nutrients_cider_and_mead";
        List<String> wrong = new ArrayList<>();
        for (String id : List.of("siftec:pickled_tomato", "siftec:pickled_kelp", "siftec:apple_jam", "siftec:melon_jam")) {
            Milestone m = Locks.lockOf(item(id));
            if (m == null || !m.id().equals(preserved)) wrong.add(id + "=" + (m == null ? "free" : m.id()));
        }
        Milestone vinegar = Locks.lockOf(item("siftec:vinegar_bottle")), mead = Locks.lockOf(item("siftec:mead"));
        expect(source, "4: pickles and jams are the Preserved Foods research's whatever makes them (food:preserves)", wrong.isEmpty(), wrong.toString());
        expect(source, "4: vinegar is Bulk Fermentation's (food:vinegar), mead Cider and Mead's (food:brewing)",
            vinegar != null && vinegar.id().equals(fermenting) && mead != null && mead.id().equals(brewing),
            (vinegar == null ? "free" : vinegar.id()) + ", " + (mead == null ? "free" : mead.id()));
        expect(source, "4: the tokens are read: each names the research that carries it",
            Locks.carrier("food:preserves") != null && Locks.carrier("food:preserves").id().equals(preserved)
                && Locks.carrier("food:vinegar") != null && Locks.carrier("food:vinegar").id().equals(fermenting)
                && Locks.carrier("food:brewing") != null && Locks.carrier("food:brewing").id().equals(brewing), "");
        FakePlayer fake = FakePlayer.get(level);
        expect(source, "4: a company without Preserved Foods may not throw a jar", !PreserveItem.mayThrow(fake), "");
    }

    // ------------------------------------------------------------------------------------------------ 5: vehicles

    private static void vehicles(CommandSourceStack source, ServerLevel level) {
        expect(source, "5: the Explorer burns Satisfactory 1.0's 90 MW", VehicleKind.EXPLORER.mw == 90, VehicleKind.EXPLORER.mw + " MW");
        BlockPos at = new BlockPos(X + 20, Y, Z + 10);
        VehicleEntity truck = new VehicleEntity(Vehicles.type(VehicleKind.TRUCK), level);
        truck.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 180, 0);
        level.addFreshEntity(truck);
        truck.setPos(truck.position());
        AABB box = truck.getBoundingBox();
        expect(source, "5: a Truck facing north takes its whole length (5) along z and its width (3) along x",
            Math.abs(box.getZsize() - 5.0) < 0.01 && Math.abs(box.getXsize() - 3.0) < 0.01, box.getXsize() + " by " + box.getZsize());
        // picked as a player picks: its front end, past where the old square box ended, is the Truck
        FakePlayer eye = FakePlayer.get(level);
        Vec3 nose = truck.local(0, 1.5, 2.2);
        boolean front = pick(eye, truck, nose) && truck.bodyHit(nose.add(0, 3, 0), nose.add(0, -3, 0), 0).isPresent();
        // turned half way, the corner of the box round it is empty: a pick there goes through
        truck.setYRot(225);
        truck.setPos(truck.position());
        Vec3 corner = truck.position().add(2.6, 1.5, 2.6);
        boolean inBox = truck.getBoundingBox().contains(corner), hitsCorner = pick(eye, truck, corner);
        expect(source, "5: it is picked by its front end, and not in the empty corner of the box round it when it is turned",
            front && inBox && !hitsCorner, "front " + front + ", corner in its box " + inBox + ", corner picked " + hitsCorner);
        expect(source, "5: a vehicle's dismantle outline is its turned body", truck.bodyCorners(0.05).length == 8
            && truck.bodyCorners(0)[0].distanceTo(truck.bodyCorners(0)[2]) > 5.8, "");
        // driving into a wall: its front stops at the wall, not its middle
        truck.setYRot(180);
        BlockPos wallAt = at.north(5);
        for (int dx = -3; dx <= 3; dx++) for (int dy = 0; dy < 4; dy++) level.setBlock(wallAt.offset(dx, dy, 0), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        truck.addFuel(10_000);
        truck.pilot = new VehicleInputPayload(true, false, false, false, false);
        later(level, 80, () -> {
            truck.pilot = null;
            double front2 = truck.getZ() - VehicleKind.TRUCK.boxLength / 2.0;
            expect(source, "5: driven at a wall, the Truck's front end stops at it (its body never goes into the wall)",
                front2 >= wallAt.getZ() + 1 - 0.3 && front2 < wallAt.getZ() + 1 + 0.8, "front at z " + String.format("%.2f", front2) + ", wall face at z " + (wallAt.getZ() + 1));
            truck.discard();
        });
    }

    /** Whether a pick straight down through a spot (as the game picks entities) finds the vehicle. */
    private static boolean pick(net.minecraft.world.entity.Entity eye, VehicleEntity v, Vec3 spot) {
        Vec3 from = spot.add(0, 3, 0), to = spot.add(0, -3, 0);
        var hit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(eye, from, to, new AABB(from, to).inflate(1), e -> e == v, 64);
        return hit != null && hit.getEntity() == v;
    }

    // ------------------------------------------------------------------------------------------------ 6: SAM

    private static void samText(CommandSourceStack source) {
        com.google.gson.JsonObject lang = null;
        try (var in = Siftec.class.getResourceAsStream("/assets/siftec/lang/en_us.json")) {
            if (in != null) lang = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException ignored) {
        }
        String text = lang == null || !lang.has("siftec.milestone.mam_alien_sam_reanimation.unlocks") ? "" : lang.get("siftec.milestone.mam_alien_sam_reanimation.unlocks").getAsString();
        expect(source, "6: SAM Reanimation's unlock is a Constructor recipe, not fan haunting", text.contains("Constructor") && !text.contains("haunting"), text);
    }

    // ------------------------------------------------------------------------------------------------ 7: Drone Port

    /** A Drone Port building put down (creative, facing north) with its terminal at a spot; its terminal, or null. */
    public static @Nullable DronePortBlockEntity buildDronePort(ServerLevel level, @Nullable Company company, BlockPos terminal) {
        BuildingDef def = Buildings.def("drone_port");
        BuildingTemplate t = def == null ? null : BuildingTemplate.of(def);
        int[] cell = t == null ? null : com.meakaandre.siftec.building.SpecialBuildings.terminalCell(t);
        if (cell == null) return null;
        Placement p = com.meakaandre.siftec.building.SpecialBuildings.forTerminal(def, cell, terminal, Direction.NORTH);
        Buildings.Check check = Buildings.place(level, p, company, null, true);
        if (!check.ok()) Siftec.LOGGER.info("SIFTEC: selftest Drone Port at {} not placed: {}", terminal.toShortString(), check.message().getString());
        return check.ok() && level.getBlockEntity(terminal) instanceof DronePortBlockEntity d ? d : null;
    }

    private static @Nullable DronePortBlockEntity port(CommandSourceStack source, ServerLevel level, Company company, BlockPos terminal) {
        DronePortBlockEntity d = buildDronePort(level, company, terminal);
        if (d == null) expect(source, "7: a Drone Port building goes down with its terminal at " + terminal.toShortString(), false, "");
        return d;
    }

    private static void dronePort(CommandSourceStack source, ServerLevel level) {
        Company c = company(level, COMPANY, "aeronautical_engineering");
        BuildingDef def = Buildings.def("drone_port");
        expect(source, "7: the Drone Port is a Build Gun building: Logistics tab, Aeronautical Engineering, Satisfactory 1.0's cost",
            def != null && def.category() == Category.LOGISTICS && "aeronautical_engineering".equals(def.unlock()) && def.cost().size() == 5,
            def == null ? "no building" : def.category() + " " + def.unlock() + " " + def.cost());
        BlockPos home = new BlockPos(X + 60, Y + 1, Z + 30), far = home.east(60);
        DronePortBlockEntity a = port(source, level, c, home), b = port(source, level, c, far);
        if (a == null || b == null) return;
        BlockPos controller = BuildingIndex.controllerOf(level, home);
        boolean listed = false;
        for (Places.Place place : Places.of(level.getServer(), c.id)) listed |= Places.same(place, level, home);
        expect(source, "7: its terminal is the company's, on the company's list of ports, part of the building",
            a.companyId.equals(c.id) && listed && controller != null, "company " + a.companyId + ", listed " + listed);
        Vec3 pad = a.pad();
        expect(source, "7: the landing pad is ten blocks over the terminal, and it is the deck",
            pad.y == home.getY() + DronePortBlockEntity.PAD_DY && !level.getBlockState(BlockPos.containing(pad.x, pad.y - 1, pad.z)).isAir(),
            "pad at " + pad + ", under it " + level.getBlockState(BlockPos.containing(pad.x, pad.y - 1, pad.z)));
        // the ports: packages at the back, fire charges at the left, arrivals out at the right
        ControllerBlockEntity ctrl = controller != null && level.getBlockEntity(controller) instanceof ControllerBlockEntity cc ? cc : null;
        Placement p = ctrl == null ? null : ctrl.placement();
        long packagesIn = 0, chargesIn = 0, arrivalsOut = 0;
        if (p != null) {
            Storage<ItemVariant> back = portStorage(level, p.at(0, 0, 18)), left = portStorage(level, p.at(-6, 0, 11)), right = portStorage(level, p.at(6, 0, 11));
            ItemStack parcel = PackageItem.containing(List.of(new ItemStack(Items.IRON_INGOT, 4)));
            try (Transaction tx = Transaction.openOuter()) {
                if (back != null) packagesIn = back.insert(ItemVariant.of(parcel), 1, tx);
                if (left != null) chargesIn = left.insert(ItemVariant.of(Items.FIRE_CHARGE), 4, tx);
                tx.commit();
            }
            a.items.setItem(PortContainer.IN, PackageItem.containing(List.of(new ItemStack(Items.COPPER_INGOT, 2))));
            try (Transaction tx = Transaction.openOuter()) {
                if (right != null) arrivalsOut = right.extract(ItemVariant.of(a.items.getItem(PortContainer.IN)), 1, tx);
                tx.commit();
            }
        }
        expect(source, "7: packages go in at the back port, fire charges at the left one, arrivals come out of the right one",
            packagesIn == 1 && chargesIn == 4 && arrivalsOut == 1 && PackageItem.isPackage(a.items.getItem(PortContainer.OUT)) && a.fuel().getCount() == 4,
            "packages " + packagesIn + ", charges " + chargesIn + ", out " + arrivalsOut);
        // a trip: the drone sits on the pad, takes off straight up, flies over and lands on the other pad
        a.hasDrone = true;
        a.setChanged();
        later(level, 5, () -> {
            List<DroneEntity> parked = level.getEntitiesOfClass(DroneEntity.class, new AABB(BlockPos.containing(pad)).inflate(2));
            expect(source, "7: the drone sits on its pad at home, as the Cardboard Drone model (not a block display)",
                parked.size() == 1 && !parked.getFirst().flying() && Math.abs(parked.getFirst().getY() - pad.y) < 0.01 && !parked.getFirst().shouldBeSaved(),
                parked.size() + " drones on the pad" + (parked.isEmpty() ? "" : " at " + parked.getFirst().position()));
            a.destination = far;
            a.setChanged();
        });
        int charges = 2;
        later(level, 40, () -> {
            List<DroneEntity> up = level.getEntitiesOfClass(DroneEntity.class, new AABB(BlockPos.containing(pad)).inflate(70, 20, 4));
            DroneEntity d = up.isEmpty() ? null : up.getFirst();
            expect(source, "7: it takes off from the pad, its propellers turning, carrying the package",
                a.state == DronePortBlockEntity.OUTBOUND && d != null && d.flying() && d.loaded() && d.getY() > pad.y + 0.5,
                "state " + a.state + (d == null ? ", no drone" : ", drone at " + d.position()));
        });
        for (int t = 60; t <= 180; t += 30) later(level, t, a::land);
        later(level, 220, () -> {
            ItemStack arrived = b.items.getItem(PortContainer.IN);
            expect(source, "7: the trip still works pad to pad: the package arrives, the drone comes home, the fire charges are spent",
                PackageItem.isPackage(arrived) && a.state == DronePortBlockEntity.IDLE && a.fuel().getCount() == 4 - charges,
                "arrived " + arrived + ", home state " + a.state + ", charges " + a.fuel().getCount());
        });
    }

    private static @Nullable Storage<ItemVariant> portStorage(ServerLevel level, BlockPos port) {
        var state = level.getBlockState(port);
        return state.getBlock() instanceof PortBlock ? ItemStorage.SIDED.find(level, port, state.getValue(PortBlock.FACING)) : null;
    }

    // ------------------------------------------------------------------------------------------------ 8: chutes

    private static void chutes(CommandSourceStack source, ServerLevel level) {
        // a chest that is a building's (listed under a building's controller, as its machines are) and one that is not
        ControllerBlockEntity owner = place(source, level, null, "test_building", new BlockPos(X + 100, Y, Z), Direction.NORTH);
        if (owner == null) return;
        BlockPos sealedChest = new BlockPos(X + 110, Y, Z), openChest = new BlockPos(X + 114, Y, Z);
        BlockPos sealedFrom = new BlockPos(X + 110, Y + 3, Z), openFrom = new BlockPos(X + 114, Y + 3, Z);
        for (BlockPos chest : List.of(sealedChest, openChest)) level.setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH), Block.UPDATE_ALL);
        java.util.Objects.requireNonNull(BuildingIndex.get(level)).put(level, sealedChest, owner.getBlockPos());
        // a chute into each from above, and a chute under each taking out of it
        BlockState chute = block("create:chute").defaultBlockState();
        for (BlockPos top : List.of(sealedFrom, openFrom)) {
            level.setBlock(top, chute, Block.UPDATE_ALL);
            level.setBlock(top.below(), chute, Block.UPDATE_ALL);
            level.setBlock(top.below(2), chute, Block.UPDATE_ALL);
        }
        later(level, 5, () -> {
            if (level.getBlockEntity(sealedFrom) instanceof com.zurrtum.create.content.logistics.chute.ChuteBlockEntity a) a.setItem(new ItemStack(Items.COBBLESTONE, 8));
            if (level.getBlockEntity(openFrom) instanceof com.zurrtum.create.content.logistics.chute.ChuteBlockEntity b) b.setItem(new ItemStack(Items.COBBLESTONE, 8));
        });
        later(level, 80, () -> {
            int sealedIn = level.getBlockEntity(sealedChest) instanceof ChestBlockEntity c ? countIn(c) : -1;
            int openIn = level.getBlockEntity(openChest) instanceof ChestBlockEntity c ? countIn(c) : -1;
            expect(source, "8: a chute from outside puts nothing into a building's machine; into anything else it does",
                sealedIn == 0 && openIn == 8, "building's " + sealedIn + ", other " + openIn);
            expect(source, "8: chutes and smart chutes (a Chute underneath) stop at the building: only its ports take and give",
                Buildings.sealed(level, sealedFrom.below(2), sealedChest) && !Buildings.sealed(level, openFrom.below(2), openChest), "");
            java.util.Objects.requireNonNull(BuildingIndex.get(level)).remove(level, sealedChest, owner.getBlockPos());
        });
    }

    private static int countIn(ChestBlockEntity chest) {
        int n = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) n += chest.getItem(i).getCount();
        return n;
    }

    // ------------------------------------------------------------------------------------------------ 10: HUB burners

    private static void hubBurners(CommandSourceStack source, ServerLevel level) {
        Company c = company(level, "selftest_polish_hub", "hub_upgrade_1", "hub_upgrade_2", "hub_upgrade_3", "hub_upgrade_4", "hub_upgrade_5", "hub_upgrade_6");
        Prefabs.Spot spot = new Prefabs.Spot(Prefabs.Kind.HUB, new BlockPos(X + 180, Y, Z + 40), Direction.NORTH);
        Prefabs.Check placed = Prefabs.place(level, spot, c, null);
        if (!placed.ok()) {
            expect(source, "10: the HUB goes down", false, placed.message().getString());
            return;
        }
        HubBurnerBlockEntity burner = level.getBlockEntity(spot.at(Templates.BURNER_1[0], Templates.BURNER_1[1], Templates.BURNER_1[2])) instanceof HubBurnerBlockEntity h ? h : null;
        if (burner == null) {
            expect(source, "10: the HUB has its first Biomass Burner", false, "");
            return;
        }
        BlockPos blaze = burner.part(1, 1, 5), engine = burner.part(1, 5, 4), coil = burner.part(-1, 5, 2), brushes = burner.part(-2, 5, 2);
        expect(source, "10: behind each HUB burner stand a Blaze Burner under a boiler, a Steam Engine, a generator coil and carbon brushes",
            level.getBlockState(blaze).is(block("create:blaze_burner")) && level.getBlockState(engine).is(block("create:steam_engine"))
                && level.getBlockState(coil).is(block("create_new_age:generator_coil")) && level.getBlockState(brushes).is(block("create_new_age:carbon_brushes")),
            level.getBlockState(blaze) + " / " + level.getBlockState(engine) + " / " + level.getBlockState(coil) + " / " + level.getBlockState(brushes));
        burner.addFuel(new ItemStack(item("siftec:biomass"), 32));
        // a Power Storage on its grid takes all it gives
        ControllerBlockEntity storage = PowerTests.build(level, source, "power_storage", new BlockPos(X + 215, Y, Z + 100), Direction.NORTH);
        BlockPos connectorAt = spot.at(Templates.BURNER_1[0], Templates.BURNER_1[1] + 1, Templates.BURNER_1[2]);
        boolean wired = PowerTests.wire(
            level.getBlockEntity(connectorAt) instanceof org.antarcticgardens.cna.content.electricity.connector.AbstractElectricalConnector ca ? ca : null,
            PowerTests.connector(level, storage));
        later(level, 300, () -> {
            boolean lit = level.getBlockEntity(blaze) instanceof BlazeBurnerBlockEntity b && b.getActiveFuel() != BlazeBurnerBlockEntity.FuelType.NONE;
            float speed = level.getBlockEntity(coil) instanceof KineticBlockEntity k ? k.getSpeed() : 0;
            double gave = burner.lastGenerated, full = com.meakaandre.siftec.power.Power.su(HubBurnerBlockEntity.MW);
            expect(source, "10: fed Biomass, its Blaze Burner burns, the boiler turns the coil, and the grid gets the burner's 30 MW from the brushes",
                wired && lit && speed != 0 && Math.abs(gave - full) <= full * 0.1,
                "wired " + wired + ", lit " + lit + ", coil " + speed + " RPM, gave " + Math.round(gave) + " of " + Math.round(full) + " SU; " + burner.status().getString());
            expect(source, "10: it takes the Biomass for Satisfactory's energy, a stack lasting minutes", burner.fuel.getItem(0).getCount() >= 28,
                burner.fuel.getItem(0).getCount() + " left");
        });
    }

    // ------------------------------------------------------------------------------------------------ 2: geysers

    private static void geysers(CommandSourceStack source, ServerLevel level) {
        Milestone geo = Milestones.get("mam_sulfur_geothermal_power");
        Company none = company(level, COMPANY);
        Company geoCo = company(level, RICH, "mam_sulfur_geothermal_power");
        expect(source, "2: Geothermal Power puts geysers on the Object Scanner", geo != null && geo.tokens().contains("object:geysers")
            && !ObjectScannerItem.open(none, false).contains(ObjectScannerItem.Target.GEYSER) && ObjectScannerItem.open(geoCo, false).contains(ObjectScannerItem.Target.GEYSER), "");
        // a sulfur cave the world tests found geysers in: its land read for geysers again (as land nobody has loaded is)
        BlockPos cave = new BlockPos(1260, 0, 730);
        GeyserSearch.forget(level.getServer(), cave, 6);
        long t0 = System.nanoTime();
        pending++;
        GeyserSearch.search(level, cave, 6, found -> {
            pending--;
            List<BlockPos> near = MapTerrain.geysersNear(cave.getX(), cave.getZ(), 6 * 16 + 16);
            boolean onMap = false;
            if (!found.isEmpty()) {
                BlockPos g = found.getFirst();
                for (MapPoi poi : MapTerrain.foundFor(MapGrid.regionOfBlock(g.getX(), g.getZ()), geoCo.id)) onMap |= poi.kind() == MapPoi.Kind.GEYSER && poi.x() == g.getX() && poi.z() == g.getZ();
            }
            expect(source, "2: a search has the sulfur cave chunks generated and reads their geysers onto the map (the Radar Tower's, and the scanner's)",
                !found.isEmpty() && !near.isEmpty() && onMap, found.size() + " found in " + (System.nanoTime() - t0) / 1_000_000 + " ms, " + near.size() + " near, on the map " + onMap);
        });
        // land nobody has loaded, far out: the search goes through by itself
        BlockPos away = new BlockPos(-30000, 0, 30000);
        pending++;
        GeyserSearch.search(level, away, 4, found -> {
            pending--;
            report(source, "2: unexplored land at " + away.toShortString() + ": " + found.size() + " geysers found");
        });
    }
}
