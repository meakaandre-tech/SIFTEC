package com.meakaandre.siftec.command;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildGun;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.BuildingTemplate;
import com.meakaandre.siftec.building.BuildingTests;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.Placement;
import com.meakaandre.siftec.building.SpecialBuildings;
import com.meakaandre.siftec.building.TruckStations;
import com.meakaandre.siftec.claim.Claims;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.HubBlockEntity;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.mam.MamBlock;
import com.meakaandre.siftec.mam.MamBlockEntity;
import com.meakaandre.siftec.prefab.Prefabs;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.vehicle.VehicleEntity;
import com.meakaandre.siftec.vehicle.Vehicles;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.catnip.math.BlockFace;
import com.zurrtum.create.content.contraptions.StructureTransform;
import com.zurrtum.create.content.contraptions.bearing.BearingContraption;
import com.zurrtum.create.content.fluids.OpenEndedPipe;
import com.zurrtum.create.infrastructure.fluids.FluidStack;
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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /siftec selftest ownership}: the checks for the companies and ownership fixes, on a test site of their own.
 * Every line prints "SELFTEST ownership"; failures say FAIL; the last line counts them.
 * <ul>
 * <li>3: the MAM is its owner company's alone, and only after Field Research; the member multiplier never touches a
 * MAM node's cost (or its parts paid), only milestones and Wormhole phases;</li>
 * <li>6: a folded company's id still owns what it stamped (buildings, vehicles, stations), the Gateway and coupons
 * printed move to the company that takes it over, and parts paid keep their share under both multipliers;</li>
 * <li>7: a Truck Station serves and is switched only by its own company, the Radar Tower is its company's to use, and
 * the Wormhole Gateway is not taken down by sneak-using it or by the game's block-breaking call;</li>
 * <li>19: dispensers, open-ended pipes and contraptions putting their blocks back respect claims.</li>
 * </ul>
 */
public final class OwnershipFixTests {
    private static final int X = 9600, Y = 200, Z = -9600;
    private static final String A = "selftest_ownership_a", B = "selftest_ownership_b", OLD = "selftest_ownership_old", FOE = "selftest_ownership_foe";
    private static final UUID MEMBER_A = UUID.fromString("0e7a5000-0000-4000-8000-000000000001");
    private static final UUID MEMBER_B = UUID.fromString("0e7a5000-0000-4000-8000-000000000002");
    private static final UUID MEMBER_OLD = UUID.fromString("0e7a5000-0000-4000-8000-000000000003");
    private static final UUID EXTRA_1 = UUID.fromString("0e7a5000-0000-4000-8000-000000000004");
    private static final UUID EXTRA_2 = UUID.fromString("0e7a5000-0000-4000-8000-000000000005");
    private static final UUID MEMBER_FOE = UUID.fromString("0e7a5000-0000-4000-8000-000000000006");

    private record Later(long at, Runnable run) {
    }

    private static final List<Later> LATER = new ArrayList<>();
    private static int passed, failed, pending;

    private OwnershipFixTests() {
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
                .then(Commands.literal("selftest").then(Commands.literal("ownership").executes(OwnershipFixTests::run)))));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static void later(ServerLevel level, int ticks, Runnable run) {
        pending++;
        LATER.add(new Later(level.getServer().overworld().getGameTime() + ticks, () -> {
            run.run();
            pending--;
        }));
    }

    private static void report(CommandSourceStack source, String text) {
        String line = "SELFTEST ownership " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        report(source, (ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    /** A test company, made afresh: the id's old record, if any, is replaced. */
    private static Company company(ServerLevel level, String id, @Nullable UUID member, String... done) {
        CompanyData data = CompanyData.get(level.getServer());
        Company c = new Company();
        c.id = id;
        c.name = id;
        if (member != null) {
            c.members.add(member.toString());
            c.owner = member.toString();
        }
        c.done.addAll(List.of(done));
        data.companies().put(id, c);
        data.setDirty();
        return c;
    }

    private static FakePlayer fake(ServerLevel level, UUID id, String name) {
        FakePlayer fake = FakePlayer.get(level, new GameProfile(id, name));
        fake.getInventory().clearContent();
        fake.setShiftKeyDown(false);
        return fake;
    }

    private static void use(ServerLevel level, FakePlayer player, BlockPos pos) {
        player.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 2.5);
        level.getBlockState(pos).useWithoutItem(level, player, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    // ------------------------------------------------------------------------------------------------ the test

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        passed = failed = pending = 0;
        BuildingTests.platform(level, X - 8, Z - 8, X + 120, Z + 156, Y, 64);
        for (VehicleEntity v : level.getEntitiesOfClass(VehicleEntity.class, new AABB(X - 8, Y - 80, Z - 8, X + 120, Y + 64, Z + 156))) v.discard();
        later(level, 10, () -> {
            run(source, "3 MAM", () -> mam(source, level));
            run(source, "6 folding", () -> folding(source, level));
            run(source, "7 stations and radar", () -> stations(source, level));
            run(source, "7 gateway", () -> gateway(source, level));
            run(source, "19 claims", () -> claims(source, level));
            finish(source, level);
        });
        report(source, "test site ready; the checks report over the next few seconds");
        return 1;
    }

    private static void run(CommandSourceStack source, String part, Runnable checks) {
        try {
            checks.run();
        } catch (RuntimeException e) {
            Siftec.LOGGER.error("SIFTEC: selftest ownership {} threw", part, e);
            expect(source, part + " runs to the end", false, e.toString());
        }
    }

    private static void finish(CommandSourceStack source, ServerLevel level) {
        if (pending > 0) {
            LATER.add(new Later(level.getServer().overworld().getGameTime() + 10, () -> finish(source, level)));
            return;
        }
        report(source, "done: " + passed + " passed, " + failed + " failed");
    }

    // ------------------------------------------------------------------------------------------------ 3: the MAM

    private static void mam(CommandSourceStack source, ServerLevel level) {
        Company a = company(level, A, MEMBER_A, MamBlock.FIELD_RESEARCH);
        company(level, B, MEMBER_B, MamBlock.FIELD_RESEARCH);
        BlockPos at = new BlockPos(X + 4, Y, Z + 4);
        level.setBlock(at, ModBlocks.MAM.get().defaultBlockState(), Block.UPDATE_ALL);
        if (!(level.getBlockEntity(at) instanceof MamBlockEntity mam)) {
            expect(source, "3: the MAM has a block entity that remembers its company", false, "");
            return;
        }
        mam.companyId = A;
        FakePlayer owner = fake(level, MEMBER_A, "selftest_own_a"), other = fake(level, MEMBER_B, "selftest_own_b");
        boolean ownerMay = MamBlock.refusal(owner, at) == null, otherMay = MamBlock.refusal(other, at) == null;
        a.done.remove(MamBlock.FIELD_RESEARCH);
        boolean before = MamBlock.refusal(owner, at) == null;
        a.done.add(MamBlock.FIELD_RESEARCH);
        expect(source, "3: only the MAM's own company uses it, and only after Field Research",
            ownerMay && !otherMay && !before, "owner " + ownerMay + ", another company " + otherMay + ", owner before Field Research " + before);
        level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // the member multiplier: three members cost x2 on milestones and phases, never on a MAM node
        a.members.add(EXTRA_1.toString());
        a.members.add(EXTRA_2.toString());
        Milestone node = Milestones.trees().isEmpty() || Milestones.trees().getFirst().nodes().isEmpty() ? null : Milestones.trees().getFirst().nodes().getFirst();
        Milestone hub = Milestones.get("hub_upgrade_1"), phase = Milestones.get("phase_1");
        if (node == null || hub == null || phase == null || node.cost().isEmpty() || hub.cost().isEmpty() || phase.cost().isEmpty()) {
            expect(source, "3: a MAM node, a milestone and a phase with costs exist", false, node + " / " + hub + " / " + phase);
            return;
        }
        Milestone.Cost nc = node.cost().getFirst(), hc = hub.cost().getFirst(), pc = phase.cost().getFirst();
        int nodeNeed = a.needed(node, nc), hubNeed = a.needed(hub, hc), phaseNeed = a.needed(phase, pc);
        expect(source, "3: with three members a MAM node costs what it lists; milestones and Wormhole phases cost x2",
            nodeNeed == nc.count() && hubNeed == hc.count() * 2 && phaseNeed == pc.count() * 2,
            "node " + nodeNeed + " of " + nc.count() + ", milestone " + hubNeed + " of " + hc.count() + ", phase " + phaseNeed + " of " + pc.count());
        // parts already delivered: a MAM node's stay as they are when the company grows, a milestone's keep their share
        a.members.remove(EXTRA_1.toString());
        a.members.remove(EXTRA_2.toString());
        a.paid.clear();
        a.paidAt = 0;
        a.syncPaid();
        a.paid.computeIfAbsent(node.id(), k -> new HashMap<>()).put(nc.key(), 2);
        a.paid.computeIfAbsent(hub.id(), k -> new HashMap<>()).put(hc.key(), 4);
        a.members.add(EXTRA_1.toString());
        a.members.add(EXTRA_2.toString());
        a.syncPaid();
        int nodePaid = a.paid.get(node.id()).get(nc.key()), hubPaid = a.paid.get(hub.id()).get(hc.key());
        expect(source, "3: as the company grows a MAM node's parts paid are not rescaled; a milestone's are",
            nodePaid == 2 && hubPaid == 8, "node " + nodePaid + " (2 paid), milestone " + hubPaid + " (4 paid at x1, 8 at x2)");
        a.members.remove(EXTRA_1.toString());
        a.members.remove(EXTRA_2.toString());
        a.paid.clear();
        a.paidAt = 0;
    }

    // ------------------------------------------------------------------------------------------------ 6: folding

    private static void folding(CommandSourceStack source, ServerLevel level) {
        CompanyData data = CompanyData.get(level.getServer());
        // mergePaid: the old company paid at x1.5 (a member who left recently still counted); the target pays at x2
        Company from = company(level, OLD, null), to = company(level, A, MEMBER_A);
        to.members.add(EXTRA_1.toString());
        to.members.add(EXTRA_2.toString());
        to.syncPaid();
        Milestone hub = Milestones.get("hub_upgrade_2");
        Milestone node = Milestones.trees().isEmpty() || Milestones.trees().getFirst().nodes().isEmpty() ? null : Milestones.trees().getFirst().nodes().getFirst();
        if (hub == null || node == null || hub.cost().isEmpty() || node.cost().isEmpty()) {
            expect(source, "6: a milestone and a MAM node with costs exist", false, "");
            return;
        }
        String hk = hub.cost().getFirst().key(), nk = node.cost().getFirst().key();
        from.paidAt = 1.5f;
        from.paid.computeIfAbsent(hub.id(), k -> new HashMap<>()).put(hk, 15);
        from.paid.computeIfAbsent(node.id(), k -> new HashMap<>()).put(nk, 3);
        Companies.mergePaid(from, to);
        int hubGot = to.paid.getOrDefault(hub.id(), Map.of()).getOrDefault(hk, 0), nodeGot = to.paid.getOrDefault(node.id(), Map.of()).getOrDefault(nk, 0);
        expect(source, "6: parts a folding company paid keep their share of the cost: 15 at x1.5 is 20 at x2, and a MAM node's 3 stay 3",
            hubGot == 20 && nodeGot == 3, "milestone " + hubGot + ", MAM node " + nodeGot);
        to.members.remove(EXTRA_1.toString());
        to.members.remove(EXTRA_2.toString());
        to.paid.clear();
        to.paidAt = 0;

        // the fold itself: the Gateway and coupons printed come along, and the old id still owns what it stamped
        Company old = company(level, OLD, MEMBER_OLD, "vehicular_transport");
        Company target = company(level, A, MEMBER_A, "vehicular_transport");
        old.gateway = "minecraft:overworld|" + new BlockPos(X + 1, Y, Z + 1).asLong();
        old.couponsPrinted = 3;
        target.couponsPrinted = 2;
        BuildingDef stationDef = Buildings.def("truck_station"), tractorDef = Buildings.def("tractor");
        if (stationDef == null || tractorDef == null) return;
        Placement sp = new Placement(stationDef, new BlockPos(X + 20, Y, Z + 30), Direction.NORTH, false);
        Buildings.Check placed = Buildings.place(level, sp, old, null, true);
        ControllerBlockEntity station = BuildGun.controllerAt(level, sp.at(0, 1, 1));
        VehicleEntity mine = Vehicles.spawn(level, new Placement(tractorDef, new BlockPos(X + 40, Y, Z + 30), Direction.EAST, false), target, List.of());
        VehicleEntity oldTractor = Vehicles.spawn(level, new Placement(tractorDef, new BlockPos(X + 40, Y, Z + 36), Direction.EAST, false), old, List.of());
        if (!placed.ok() || station == null || mine == null || oldTractor == null) {
            expect(source, "6: a Truck Station and two Tractors go down", false, placed.message().getString());
            return;
        }
        old.members.remove(MEMBER_OLD.toString());
        Companies.fold(level.getServer(), old, target);
        target.members.add(MEMBER_OLD.toString());
        FakePlayer member = fake(level, MEMBER_A, "selftest_own_a");
        boolean resolves = data.byId(OLD) == target;
        boolean operates = SpecialBuildings.mayOperate(member, station);
        boolean serves = TruckStations.serves(station, mine);
        member.setPos(oldTractor.getX() + 2, oldTractor.getY(), oldTractor.getZ());
        boolean vehicle = oldTractor.mayKeepCargoOpen(member);
        boolean dismantle = BuildGun.mayDismantle(member, target, OLD, new AABB(station.getBlockPos())) == null;
        expect(source, "6: after the fold the old company's id answers to the new one: its station, vehicle and building are the new company's",
            resolves && operates && serves && vehicle && dismantle,
            "byId " + resolves + ", station usable " + operates + ", serves the new company's Tractor " + serves + ", old Tractor's storage " + vehicle
                + ", building dismantled " + dismantle);
        expect(source, "6: the Wormhole Gateway and the coupons printed come along with the fold",
            old.gateway.equals(target.gateway) && target.couponsPrinted == 5, "gateway '" + target.gateway + "', coupons " + target.couponsPrinted);
        mine.discard();
        oldTractor.discard();
        Buildings.dismantle(level, station);
        target.gateway = "";
        target.members.remove(MEMBER_OLD.toString());
    }

    // ------------------------------------------------------------------------------------------------ 7: stations and radar

    private static void stations(CommandSourceStack source, ServerLevel level) {
        Company a = company(level, A, MEMBER_A, "vehicular_transport");
        Company b = company(level, B, MEMBER_B, "vehicular_transport");
        BuildingDef stationDef = Buildings.def("truck_station"), tractorDef = Buildings.def("tractor"), radarDef = Buildings.def("radar_tower");
        if (stationDef == null || tractorDef == null) return;
        Placement sp = new Placement(stationDef, new BlockPos(X + 60, Y, Z + 30), Direction.NORTH, false);
        Buildings.Check placed = Buildings.place(level, sp, a, null, true);
        ControllerBlockEntity station = BuildGun.controllerAt(level, sp.at(0, 1, 1));
        VehicleEntity ours = Vehicles.spawn(level, new Placement(tractorDef, new BlockPos(X + 50, Y, Z + 40), Direction.EAST, false), a, List.of());
        VehicleEntity theirs = Vehicles.spawn(level, new Placement(tractorDef, new BlockPos(X + 50, Y, Z + 46), Direction.EAST, false), b, List.of());
        if (!placed.ok() || station == null || ours == null || theirs == null) {
            expect(source, "7: a Truck Station and two Tractors go down", false, placed.message().getString());
            return;
        }
        station.unload = true;
        ours.cargo.setItem(0, new ItemStack(Items.IRON_INGOT, 5));
        theirs.cargo.setItem(0, new ItemStack(Items.IRON_INGOT, 5));
        long theirsMoved = TruckStations.dock(level, station.getBlockPos(), theirs);
        long oursMoved = TruckStations.dock(level, station.getBlockPos(), ours);
        expect(source, "7: a Truck Station serves only its own company's vehicles",
            theirsMoved == 0 && theirs.cargo.getItem(0).getCount() == 5 && oursMoved == 5 && ours.cargo.getItem(0).isEmpty(),
            "another company's Tractor moved " + theirsMoved + ", ours moved " + oursMoved);
        // switching its mode is the owner company's
        FakePlayer owner = fake(level, MEMBER_A, "selftest_own_a"), other = fake(level, MEMBER_B, "selftest_own_b");
        boolean mode = station.unload;
        use(level, other, station.getBlockPos());
        boolean afterOther = station.unload;
        use(level, owner, station.getBlockPos());
        boolean afterOwner = station.unload;
        expect(source, "7: only the station's own company switches it between loading and unloading",
            afterOther == mode && afterOwner != mode, "before " + mode + ", after another company " + afterOther + ", after its own " + afterOwner);
        ours.discard();
        theirs.discard();
        Buildings.dismantle(level, station);
        // the Radar Tower: its own company's to use
        if (radarDef == null) return;
        Placement rp = new Placement(radarDef, new BlockPos(X + 90, Y, Z + 30), Direction.NORTH, false);
        Buildings.Check radarPlaced = Buildings.place(level, rp, a, null, true);
        ControllerBlockEntity radar = null;
        BuildingTemplate rt = BuildingTemplate.of(radarDef);
        if (rt != null) radar = BuildGun.controllerAt(level, rp.at(rt.controller));
        if (!radarPlaced.ok() || radar == null) {
            expect(source, "7: a Radar Tower goes down", false, radarPlaced.message().getString());
            return;
        }
        expect(source, "7: the Radar Tower is used only by its own company's members",
            SpecialBuildings.mayOperate(owner, radar) && !SpecialBuildings.mayOperate(other, radar), "");
        Buildings.dismantle(level, radar);
    }

    // ------------------------------------------------------------------------------------------------ 7: the gateway

    private static void gateway(CommandSourceStack source, ServerLevel level) {
        Company a = company(level, A, MEMBER_A, "hub_upgrade_1", "hub_upgrade_2", "hub_upgrade_3", "hub_upgrade_4", "hub_upgrade_5", "hub_upgrade_6");
        a.gateway = "";
        Prefabs.Spot spot = new Prefabs.Spot(Prefabs.Kind.GATEWAY, new BlockPos(X + 40, Y, Z + 148), Direction.NORTH);
        Prefabs.Check placed = Prefabs.place(level, spot, a, null);
        BlockPos terminal = spot.terminal();
        if (!placed.ok() || !(level.getBlockEntity(terminal) instanceof HubBlockEntity gate)) {
            expect(source, "7: a Wormhole Gateway goes down", false, placed.message().getString());
            return;
        }
        // sneak-using its terminal twice (the old pick-up) leaves it standing
        FakePlayer owner = fake(level, MEMBER_A, "selftest_own_a");
        owner.setShiftKeyDown(true);
        use(level, owner, terminal);
        use(level, owner, terminal);
        owner.setShiftKeyDown(false);
        boolean standing = level.getBlockState(terminal).is(ModBlocks.WORMHOLE_GATEWAY.get());
        // the game's block-breaking call (a Wither's, another mod's tool) takes neither its terminal nor a part of its shell
        BlockPos part = null;
        AABB box = Prefabs.bounds(spot);
        for (BlockPos pos : BlockPos.betweenClosed((int) box.minX, (int) box.minY, (int) box.minZ, (int) box.maxX - 1, (int) box.maxY - 1, (int) box.maxZ - 1)) {
            if (!pos.equals(terminal) && Prefabs.isPart(level.getBlockState(pos))) {
                part = pos.immutable();
                break;
            }
        }
        BlockState partWas = part == null ? null : level.getBlockState(part);
        boolean brokeTerminal = level.destroyBlock(terminal, true, null);
        boolean brokePart = part != null && level.destroyBlock(part, true, null);
        boolean kept = level.getBlockState(terminal).is(ModBlocks.WORMHOLE_GATEWAY.get()) && (part == null || level.getBlockState(part) == partWas);
        expect(source, "7: the Wormhole Gateway is not taken down by sneak-using it or by the block-breaking call (only the Build Gun takes it)",
            standing && !brokeTerminal && !brokePart && kept && part != null,
            "after sneak-use " + standing + ", terminal broke " + brokeTerminal + ", part " + (part == null ? "none" : part.toShortString()) + " broke " + brokePart);
        // the Build Gun's way still takes it down
        if (level.getBlockEntity(terminal) instanceof HubBlockEntity still) Prefabs.dismantle(level, still);
        expect(source, "7: the Build Gun's dismantle still takes the Gateway down", !level.getBlockState(terminal).is(ModBlocks.WORMHOLE_GATEWAY.get()), "");
        a.gateway = "";
    }

    // ------------------------------------------------------------------------------------------------ 19: claims

    private static void claims(CommandSourceStack source, ServerLevel level) {
        Company foe = company(level, FOE, MEMBER_FOE);
        // one claimed chunk: x from X+96 to X+111, z from Z+48 to Z+63; everything west of it is unclaimed
        BlockPos source0 = new BlockPos(X + 100, Y, Z + 56);
        Claims.releaseCompany(level.getServer(), FOE);
        int claimed = Claims.claim(level, foe, source0, 0, true, 1);
        if (claimed != 1) {
            expect(source, "19: the test claim is made", false, claimed + " chunks");
            return;
        }
        try {
            dispensers(source, level);
            pipes(source, level);
            disassembly(source, level);
        } finally {
            Claims.releaseCompany(level.getServer(), FOE);
        }
    }

    private static boolean dispense(ServerLevel level, BlockPos at, Direction facing) {
        level.setBlock(at, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, facing), Block.UPDATE_ALL);
        if (!(level.getBlockEntity(at) instanceof DispenserBlockEntity d)) return false;
        d.setItem(0, new ItemStack(Items.WATER_BUCKET));
        level.getBlockState(at).tick(level, at, level.getRandom());
        return true;
    }

    private static void dispensers(CommandSourceStack source, ServerLevel level) {
        BlockPos into = new BlockPos(X + 95, Y, Z + 50), outside = new BlockPos(X + 90, Y, Z + 50);
        boolean a = dispense(level, into, Direction.EAST), b = dispense(level, outside, Direction.WEST);
        BlockPos claimedTarget = into.east(), freeTarget = outside.west();
        boolean wetClaim = !level.getFluidState(claimedTarget).isEmpty(), wetFree = !level.getFluidState(freeTarget).isEmpty();
        expect(source, "19: a dispenser outside a claim pours nothing into it (one facing unclaimed land does)",
            a && b && !wetClaim && wetFree, "into the claim " + wetClaim + ", into unclaimed land " + wetFree);
        for (BlockPos p : List.of(into, outside)) if (level.getBlockEntity(p) instanceof DispenserBlockEntity d) d.clearContent();
        for (BlockPos p : List.of(into, outside, claimedTarget, freeTarget)) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static void pipes(CommandSourceStack source, ServerLevel level) {
        // an open pipe end in unclaimed land, facing into the claim, and one facing unclaimed land
        BlockPos into = new BlockPos(X + 95, Y, Z + 54), outside = new BlockPos(X + 90, Y, Z + 54);
        OpenEndedPipe toClaim = new OpenEndedPipe(new BlockFace(into, Direction.EAST)), toFree = new OpenEndedPipe(new BlockFace(outside, Direction.WEST));
        toClaim.manageSource(level, null);
        toFree.manageSource(level, null);
        toClaim.provideHandler().insert(new FluidStack(Fluids.WATER, 81000));
        toFree.provideHandler().insert(new FluidStack(Fluids.WATER, 81000));
        boolean wetClaim = !level.getFluidState(into.east()).isEmpty(), wetFree = !level.getFluidState(outside.west()).isEmpty();
        expect(source, "19: an open-ended pipe outside a claim pours nothing into it (one facing unclaimed land does)",
            !wetClaim && wetFree, "into the claim " + wetClaim + ", into unclaimed land " + wetFree);
        level.setBlock(outside.west(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // nor drains a source block inside the claim
        BlockPos pool = new BlockPos(X + 96, Y, Z + 58), end = pool.west();
        level.setBlock(pool, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        OpenEndedPipe drain = new OpenEndedPipe(new BlockFace(end, Direction.EAST));
        drain.manageSource(level, null);
        FluidStack got = drain.provideHandler().getStack(1);
        boolean kept = level.getFluidState(pool).isSource();
        expect(source, "19: an open-ended pipe outside a claim drains nothing out of it", got.isEmpty() && kept, "got " + got.getAmount() + ", source kept " + kept);
        level.setBlock(pool, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(into.east(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static void disassembly(CommandSourceStack source, ServerLevel level) {
        // a contraption assembled in unclaimed land stops with one block over the claim and one outside it
        BlockPos anchor = new BlockPos(X + 90, Y, Z + 60);
        BlockPos inClaim = anchor.offset(6, 0, 0), outsideAt = anchor.offset(2, 0, 0);
        for (BlockPos p : List.of(inClaim, outsideAt)) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(anchor).inflate(4))) e.discard();
        BearingContraption contraption = new BearingContraption();
        contraption.anchor = anchor;
        BlockPos localIn = new BlockPos(6, 0, 0), localOut = new BlockPos(2, 0, 0);
        contraption.getBlocks().put(localIn, new StructureTemplate.StructureBlockInfo(localIn, Blocks.COBBLESTONE.defaultBlockState(), null));
        contraption.getBlocks().put(localOut, new StructureTemplate.StructureBlockInfo(localOut, Blocks.COBBLESTONE.defaultBlockState(), null));
        contraption.getStorage().initialize();
        contraption.addBlocksToWorld(level, new StructureTransform(anchor, 0f, 0f, 0f));
        boolean claimEmpty = level.getBlockState(inClaim).isAir(), outsidePlaced = level.getBlockState(outsideAt).is(Blocks.COBBLESTONE);
        int back = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(anchor).inflate(4))) if (e.getItem().is(Items.COBBLESTONE)) back += e.getItem().getCount();
        expect(source, "19: a contraption putting its blocks back places none in someone else's claim (that block comes back as its item at its anchor)",
            claimEmpty && outsidePlaced && back == 1, "claim spot empty " + claimEmpty + ", outside placed " + outsidePlaced + ", items back " + back);
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(anchor).inflate(4))) e.discard();
        for (BlockPos p : List.of(inClaim, outsideAt)) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }
}
