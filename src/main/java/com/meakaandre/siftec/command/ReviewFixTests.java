package com.meakaandre.siftec.command;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildGun;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.BuildingLogic;
import com.meakaandre.siftec.building.BuildingTemplate;
import com.meakaandre.siftec.building.BuildingTests;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.Placement;
import com.meakaandre.siftec.building.TruckStations;
import com.meakaandre.siftec.claim.Claims;
import com.meakaandre.siftec.collect.CrashSites;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.death.DeathCrateBlockEntity;
import com.meakaandre.siftec.death.DeathCrates;
import com.meakaandre.siftec.depot.DepotMenu;
import com.meakaandre.siftec.node.NodeLook;
import com.meakaandre.siftec.node.NodeRegrowth;
import com.meakaandre.siftec.node.NodeSites;
import com.meakaandre.siftec.node.NodeType;
import com.meakaandre.siftec.node.Purity;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.vehicle.VehicleEntity;
import com.meakaandre.siftec.vehicle.Vehicles;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code /siftec selftest fixes}: one check (or a few) for each fix from the full review, on a test site of its own far
 * from the other selftests. The checks that need a real player (fixes 3 and 4's backpack parts) are
 * {@code /siftec selftest tb fixes}. Every check prints "SELFTEST fixes"; failures say FAIL; the last line counts them.
 * <ol>
 * <li>node cells: a building never covers a node position, a miner takes only the node in its own node cell, a Death
 * Crate never goes on a node, and a regrowing node waits for what stands in its spot instead of deleting it;</li>
 * <li>building protection: no Deployer of another building, Designer sneak-clear, explosion or the game's block-breaking
 * call (a Wither's) takes a building's block, and a block lost anyway gives nothing back for itself;</li>
 * <li>(tb fixes)</li>
 * <li>the Death Crate never goes over a block (a full column drops the things instead), and its owner opens it in
 * another company's claim;</li>
 * <li>vehicle storage and the cloud close when the player walks off, leaves the company or the thing is gone;</li>
 * <li>a vehicle that falls out of the world comes back to its last Truck Station, or to its company's HUB;</li>
 * <li>a crash site pod's wire connector is put down once, never again;</li>
 * <li>a dismantled building's tanks come back as packaged fluid (or buckets).</li>
 * </ol>
 */
public final class ReviewFixTests {
    private static final int X = 7200, Y = 200, Z = -7200;
    private static final String COMPANY = "selftest_fixes", FOE = "selftest_fixes_foe", OTHER = "selftest_fixes_other";
    private static final UUID MEMBER = UUID.fromString("5e1f7e57-f1c5-4000-8000-000000000001");
    private static final UUID STRANGER = UUID.fromString("5e1f7e57-f1c5-4000-8000-000000000002");
    private static final UUID FOE_MEMBER = UUID.fromString("5e1f7e57-f1c5-4000-8000-000000000003");

    private record Later(long at, Runnable run) {
    }

    private static final List<Later> LATER = new ArrayList<>();
    private static int passed, failed, pending;

    private ReviewFixTests() {
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
                .then(Commands.literal("selftest").then(Commands.literal("fixes").executes(ReviewFixTests::run)))));
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
        String line = "SELFTEST fixes " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        report(source, (ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

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
        return fake;
    }

    private static Block create(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("create", id));
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    private static String at(BlockPos pos) {
        return pos == null ? "none" : pos.toShortString();
    }

    private static int count(List<ItemStack> stacks, Item item) {
        int n = 0;
        for (ItemStack s : stacks) if (s.is(item)) n += s.getCount();
        return n;
    }

    /** Puts an iron ore node here and leaves it empty (mined), held so it does not grow back by itself. */
    private static void emptyNode(ServerLevel level, BlockPos pos) {
        NodeSites.put(level, pos, NodeLook.of(NodeType.IRON), Purity.PURE);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        NodeSites.hold(level, pos);
    }

    private static void forgetNode(ServerLevel level, BlockPos pos) {
        NodeSites.remove(level, pos);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    // ------------------------------------------------------------------------------------------------ the test

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        passed = failed = pending = 0;
        BuildingTests.platform(level, X - 8, Z - 8, X + 120, Z + 72, Y, 16);
        for (VehicleEntity v : level.getEntitiesOfClass(VehicleEntity.class, new AABB(X - 8, Y - 80, Z - 8, X + 120, Y + 40, Z + 72))) v.discard();
        later(level, 10, () -> {
            run(source, "1 node cells", () -> nodeCells(source, level));
            run(source, "2 building protection", () -> protection(source, level));
            run(source, "4 death crate", () -> crate(source, level));
            run(source, "5 storage windows", () -> windows(source, level));
            run(source, "6 out of the world", () -> belowWorld(source, level));
            run(source, "7 crash site connector", () -> connector(source, level));
            run(source, "8 tank fluids", () -> fluids(source, level));
            finish(source, level);
        });
        report(source, "test site ready; the checks report over the next few seconds");
        return 1;
    }

    private static void run(CommandSourceStack source, String part, Runnable checks) {
        try {
            checks.run();
        } catch (RuntimeException e) {
            Siftec.LOGGER.error("SIFTEC: selftest fixes {} threw", part, e);
            expect(source, part + " runs to the end", false, e.toString());
        }
    }

    /** Reports once every delayed check has run. */
    private static void finish(CommandSourceStack source, ServerLevel level) {
        if (pending > 0) {
            LATER.add(new Later(level.getServer().overworld().getGameTime() + 10, () -> finish(source, level)));
            return;
        }
        report(source, "done: " + passed + " passed, " + failed + " failed");
    }

    // ------------------------------------------------------------------------------------------------ 1: node cells

    private static void nodeCells(CommandSourceStack source, ServerLevel level) {
        Company company = company(level, COMPANY, MEMBER);
        // a building over an empty node: the node position is not free space
        BuildingDef storage = Buildings.def("storage_container");
        BuildingTemplate st = storage == null ? null : BuildingTemplate.of(storage);
        if (storage != null && st != null) {
            Placement p = new Placement(storage, new BlockPos(X + 4, Y, Z + 4), Direction.SOUTH, false);
            Buildings.Check clear = Buildings.check(level, p, company, null, true);
            BlockPos node = p.at(st.members.getFirst());
            emptyNode(level, node);
            Buildings.Check over = Buildings.check(level, p, company, null, true);
            expect(source, "1: a building is refused over an empty (mined) node", clear.ok() && !over.ok() && over.blocked.contains(node),
                "clear " + clear.ok() + "; over the node at " + at(node) + ": " + over.blocked.size() + " blocked");
            forgetNode(level, node);
        }
        // a miner over two nodes takes only its own node cell; a node elsewhere in its box refuses it
        BuildingDef miner = Buildings.def("miner_mk1");
        BuildingTemplate mt = miner == null ? null : BuildingTemplate.of(miner);
        if (miner != null && mt != null) {
            Placement p = new Placement(miner, new BlockPos(X + 20, Y, Z + 4), Direction.SOUTH, false);
            BlockPos own = Buildings.ownNodeCell(p);
            AABB box = p.bounds(mt);
            BlockPos other = null;
            for (BlockPos pos : BlockPos.betweenClosed((int) box.minX, (int) box.minY, (int) box.minZ, (int) box.maxX - 1, (int) box.minY, (int) box.maxZ - 1)) {
                if (own != null && !pos.equals(own)) {
                    other = pos.immutable();
                    break;
                }
            }
            if (own == null || other == null) {
                expect(source, "1: the miner has a node cell and room for a second node", false, at(own) + " / " + at(other));
            } else {
                emptyNode(level, other);
                BlockPos onlyOther = Buildings.nodeIn(level, p, mt);
                Buildings.Check offCell = Buildings.check(level, p, company, null, true);
                emptyNode(level, own);
                BlockPos both = Buildings.nodeIn(level, p, mt);
                Buildings.Check withOther = Buildings.check(level, p, company, null, true);
                forgetNode(level, other);
                Buildings.Check alone = Buildings.check(level, p, company, null, true);
                expect(source, "1: a miner takes only the node in its own node cell", onlyOther == null && own.equals(both) && !offCell.ok(),
                    "node elsewhere only: " + at(onlyOther) + ", " + offCell.message().getString() + "; in its cell: " + at(both));
                expect(source, "1: another node inside a miner's box refuses it; on its own node alone it fits",
                    !withOther.ok() && withOther.blocked.contains(other) && alone.ok(), withOther.message().getString() + " / " + alone.message().getString());
                forgetNode(level, own);
            }
        }
        // a Death Crate never goes on a node position
        BlockPos died = new BlockPos(X + 40, Y, Z + 4);
        emptyNode(level, died);
        BlockPos spot = DeathCrates.spot(level, died);
        expect(source, "1: a Death Crate does not go on an empty node where its owner died", spot != null && !spot.equals(died) && !DeathCrates.free(level, died),
            "died at " + at(died) + ", crate at " + at(spot));
        forgetNode(level, died);
        // regrowth never deletes a block entity in the spot: it waits, and grows back once the spot is clear
        BlockPos node = new BlockPos(X + 44, Y, Z + 4);
        emptyNode(level, node);
        level.setBlock(node, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        if (level.getBlockEntity(node) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) chest.setItem(0, new ItemStack(Items.DIAMOND, 4));
        NodeSites.release(level, node);
        int regrow = NodeRegrowth.ticks(Purity.PURE);
        later(level, regrow + 10, () -> {
            boolean kept = level.getBlockEntity(node) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest && chest.getItem(0).is(Items.DIAMOND)
                && chest.getItem(0).getCount() == 4;
            expect(source, "1: a node due to grow back waits while a chest stands in its spot, and the chest and its things stay",
                kept && NodeSites.emptyAt(level, node), "spot " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(node).getBlock()));
            if (level.getBlockEntity(node) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) chest.clearContent();
            level.setBlock(node, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            later(level, 3, () -> {
                expect(source, "1: the chest taken away, the node grows back", NodeSites.grownAt(level, node),
                    "spot " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(node).getBlock()));
                forgetNode(level, node);
            });
        });
    }

    // ------------------------------------------------------------------------------------------------ 2: protection

    private static void protection(CommandSourceStack source, ServerLevel level) {
        Company company = company(level, COMPANY, MEMBER);
        BuildingDef def = Buildings.def("storage_container");
        BuildingTemplate t = def == null ? null : BuildingTemplate.of(def);
        if (def == null || t == null) {
            expect(source, "2: the storage_container building exists", false, "");
            return;
        }
        Placement p = new Placement(def, new BlockPos(X + 8, Y, Z + 24), Direction.SOUTH, false);
        Buildings.Check placed = Buildings.place(level, p, company, null, true);
        ControllerBlockEntity c = BuildGun.controllerAt(level, p.at(t.controller));
        if (!placed.ok() || c == null) {
            expect(source, "2: the test building goes down", false, placed.message().getString());
            return;
        }
        BlockPos member = null;
        for (BuildingTemplate.Cell cell : t.members) {
            BlockPos pos = p.at(cell);
            if (!pos.equals(p.at(t.controller)) && !level.getBlockState(pos).isAir()) {
                member = pos;
                break;
            }
        }
        if (member == null) return;
        BlockState was = level.getBlockState(member);
        // the game's block-breaking call (a Wither's charge, a mob, another mod's tool) leaves it standing
        boolean broke = level.destroyBlock(member, true, null);
        expect(source, "2: the game's block-breaking call (as a Wither breaks blocks) does not break a building's block",
            !broke && level.getBlockState(member) == was, "destroyBlock gave " + broke + ", now " + level.getBlockState(member));
        // an explosion over it
        level.explode(null, member.getX() + 0.5, member.getY() + 1.5, member.getZ() + 0.5, 5f, Level.ExplosionInteraction.TNT);
        int standing = 0;
        for (BuildingTemplate.Cell cell : t.members) if (level.getBlockState(p.at(cell)).getBlock() == cell.state().getBlock()) standing++;
        expect(source, "2: an explosion next to it leaves every block of the building", standing == t.members.size(), standing + " of " + t.members.size());
        // a Deployer of no building (or of another one) may not act on it; one of the same building may
        BlockPos outside = p.at(t.controller).offset(0, 0, -6);
        expect(source, "2: a Deployer outside the building may not act on its blocks; one that is part of it may",
            !Buildings.deployerMayAct(level, outside, member) && Buildings.deployerMayAct(level, p.at(t.controller), member)
                && Buildings.deployerMayAct(level, outside, outside.above()), "");
        // the Designer's sneak-clear goes round it
        AABB box = p.bounds(t);
        BlockPos designer = new BlockPos((int) Math.floor((box.minX + box.maxX) / 2), Y - 1, (int) Math.floor((box.minZ + box.maxZ) / 2));
        BlockState under = level.getBlockState(designer);
        BlockPos loose = null;
        AABB volume = com.meakaandre.siftec.blueprint.DesignerBlockEntity.volume(designer, 8);
        for (BlockPos pos : BlockPos.betweenClosed((int) volume.minX, Y, (int) volume.minZ, (int) volume.maxX - 1, Y, (int) volume.maxZ - 1)) {
            if (level.getBlockState(pos).isAir() && !Buildings.protectedAt(level, pos) && !box.contains(Vec3.atCenterOf(pos))) {
                loose = pos.immutable();
                break;
            }
        }
        if (loose != null) level.setBlock(loose, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(designer, ModBlocks.BLUEPRINT_DESIGNER_MK3.get().defaultBlockState(), Block.UPDATE_ALL);
        FakePlayer hand = fake(level, MEMBER, "selftest_fixes");
        hand.setPos(designer.getX() + 0.5, designer.getY() + 1, designer.getZ() + 0.5);
        hand.setShiftKeyDown(true);
        level.getBlockState(designer).useWithoutItem(level, hand, new BlockHitResult(Vec3.atCenterOf(designer), Direction.UP, designer, false));
        hand.setShiftKeyDown(false);
        int after = 0;
        for (BuildingTemplate.Cell cell : t.members) if (level.getBlockState(p.at(cell)).getBlock() == cell.state().getBlock()) after++;
        boolean cleared = loose == null || level.getBlockState(loose).isAir();
        expect(source, "2: the Blueprint Designer's sneak-clear leaves a building in its volume alone (and clears the rest)",
            after == t.members.size() && cleared, after + " of " + t.members.size() + " standing; loose block at " + at(loose) + " cleared " + cleared);
        level.setBlock(designer, under, Block.UPDATE_ALL);
        // a block lost anyway gives nothing back for itself
        BlockPos lost = member;
        Item own = was.getBlock().asItem();
        AABB near = new AABB(lost).inflate(3);
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, near)) e.discard();
        Block.dropResources(level.getBlockState(lost), level, lost);
        int dropped = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, near)) if (e.getItem().is(own)) dropped += e.getItem().getCount();
        expect(source, "2: a building's block that drops as it is lost gives nothing back for itself", dropped == 0,
            dropped + " " + BuiltInRegistries.ITEM.getKey(own) + " dropped");
        // the Build Gun still takes it down, with its refund
        List<ItemStack> back = Buildings.dismantle(level, c);
        int left = 0;
        for (BuildingTemplate.Cell cell : t.members) if (level.getBlockState(p.at(cell)).getBlock() == cell.state().getBlock()) left++;
        expect(source, "2: the Build Gun's dismantle still takes the building down", left == 0 && !Buildings.protectedAt(level, member), back.size() + " stacks back");
    }

    // ------------------------------------------------------------------------------------------------ 4: the death crate

    private static void crate(CommandSourceStack source, ServerLevel level) {
        // a column with no free spot anywhere: no crate goes over a block
        BlockPos column = new BlockPos(X + 60, Y, Z + 4);
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int y = level.getMinY() + 1; y < level.getMaxY(); y++) {
            BlockPos pos = column.atY(y);
            if (level.getBlockState(pos).isAir() || level.getBlockState(pos).canBeReplaced()) level.setBlock(pos, stone, Block.UPDATE_CLIENTS);
        }
        BlockPos spot = DeathCrates.spot(level, column);
        expect(source, "4: in a column full of blocks there is no spot for the crate (its things drop instead of overwriting a block)", spot == null, at(spot));
        for (int y = Y; y < level.getMaxY(); y++) level.setBlock(column.atY(y), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        // the owner opens their crate inside another company's claim; nobody else does
        Company foe = company(level, FOE, FOE_MEMBER);
        company(level, COMPANY, MEMBER);
        BlockPos crateAt = new BlockPos(X + 104, Y, Z + 56);
        int claimed = Claims.claim(level, foe, crateAt, 0, true, 1);
        level.setBlock(crateAt, ModBlocks.DEATH_CRATE.get().defaultBlockState(), Block.UPDATE_ALL);
        if (!(level.getBlockEntity(crateAt) instanceof DeathCrateBlockEntity crate)) {
            expect(source, "4: a Death Crate goes down", false, "");
            Claims.release(level, crateAt);
            return;
        }
        crate.owner = MEMBER;
        crate.ownerName = "selftest_fixes";
        crate.entries.add(new DeathCrateBlockEntity.Entry(DeathCrateBlockEntity.INVENTORY, 0, new ItemStack(Items.EMERALD, 6)));
        crate.setChanged();
        FakePlayer owner = fake(level, MEMBER, "selftest_fixes");
        FakePlayer stranger = fake(level, STRANGER, "selftest_fixes_stranger");
        for (FakePlayer f : List.of(owner, stranger)) f.setPos(X + 2.5, Y, Z + 2.5);
        boolean building = Claims.allowed(owner, level, crateAt);
        boolean ownerUses = Claims.mayUse(owner, level, crateAt), strangerUses = Claims.mayUse(stranger, level, crateAt);
        DeathCrates.open(owner, crateAt);
        boolean got = owner.getInventory().countItem(Items.EMERALD) == 6 && !(level.getBlockEntity(crateAt) instanceof DeathCrateBlockEntity);
        expect(source, "4: in another company's claim the crate's owner may open it (and gets their things), nobody else may use it",
            claimed == 1 && !building && ownerUses && !strangerUses && got,
            "claimed " + claimed + ", owner may build " + building + ", owner uses " + ownerUses + ", stranger uses " + strangerUses + ", emeralds back " + got);
        if (level.getBlockEntity(crateAt) instanceof DeathCrateBlockEntity left) left.entries.clear();
        level.setBlock(crateAt, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Claims.release(level, crateAt);
        owner.getInventory().clearContent();
    }

    // ------------------------------------------------------------------------------------------------ 5: windows

    private static void windows(CommandSourceStack source, ServerLevel level) {
        Company company = company(level, COMPANY, MEMBER, "vehicular_transport");
        BuildingDef tractorDef = Buildings.def("tractor");
        if (tractorDef == null) return;
        VehicleEntity tractor = Vehicles.spawn(level, new Placement(tractorDef, new BlockPos(X + 30, Y, Z + 50), Direction.EAST, false), company, List.of());
        if (tractor == null) {
            expect(source, "5: a Tractor goes down", false, "");
            return;
        }
        FakePlayer member = fake(level, MEMBER, "selftest_fixes");
        member.setPos(tractor.getX() + 2, tractor.getY(), tractor.getZ());
        boolean near = tractor.mayKeepCargoOpen(member);
        member.setPos(tractor.getX() + 30, tractor.getY(), tractor.getZ());
        boolean far = tractor.mayKeepCargoOpen(member);
        member.setPos(tractor.getX() + 2, tractor.getY(), tractor.getZ());
        company.members.remove(MEMBER.toString());
        boolean left = tractor.mayKeepCargoOpen(member);
        company.members.add(MEMBER.toString());
        tractor.discard();
        boolean gone = tractor.mayKeepCargoOpen(member);
        expect(source, "5: a vehicle's storage stays open only near it, for a member of its company, while it is there",
            near && !far && !left && !gone, "near " + near + ", 30 blocks off " + far + ", after leaving the company " + left + ", vehicle gone " + gone);
        // the cloud opened at a depot
        BlockPos depot = new BlockPos(X + 40, Y, Z + 50);
        level.setBlock(depot, ModBlocks.DIMENSIONAL_DEPOT.get().defaultBlockState(), Block.UPDATE_ALL);
        member.setPos(depot.getX() + 2.5, Y, depot.getZ() + 0.5);
        boolean atDepot = DepotMenu.valid(member, company, depot), anywhere = DepotMenu.valid(member, company, null);
        member.setPos(depot.getX() + 30.5, Y, depot.getZ() + 0.5);
        boolean walkedOff = DepotMenu.valid(member, company, depot), cloudButton = DepotMenu.valid(member, company, null);
        member.setPos(depot.getX() + 2.5, Y, depot.getZ() + 0.5);
        company.members.remove(MEMBER.toString());
        boolean outOfCompany = DepotMenu.valid(member, company, depot) || DepotMenu.valid(member, company, null);
        company.members.add(MEMBER.toString());
        level.setBlock(depot, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        boolean depotGone = DepotMenu.valid(member, company, depot);
        expect(source, "5: the cloud window stays open only for a member of its company, and only near the depot it was opened at while it stands",
            atDepot && anywhere && !walkedOff && cloudButton && !outOfCompany && !depotGone,
            "at the depot " + atDepot + ", from anywhere " + anywhere + ", walked off " + walkedOff + ", from anywhere far off " + cloudButton
                + ", out of the company " + outOfCompany + ", depot gone " + depotGone);
    }

    // ------------------------------------------------------------------------------------------------ 6: out of the world

    private static void belowWorld(CommandSourceStack source, ServerLevel level) {
        Company company = company(level, COMPANY, MEMBER, "vehicular_transport", "logistics_mk4");
        BuildingDef stationDef = Buildings.def("truck_station"), tractorDef = Buildings.def("tractor");
        if (stationDef == null || tractorDef == null) return;
        Placement sp = new Placement(stationDef, new BlockPos(X + 70, Y, Z + 30), Direction.NORTH, false);
        Buildings.Check placed = Buildings.place(level, sp, company, null, true);
        ControllerBlockEntity station = BuildGun.controllerAt(level, sp.at(0, 1, 1));
        Vec3 dock = station == null ? null : TruckStations.dock(station);
        VehicleEntity tractor = Vehicles.spawn(level, new Placement(tractorDef, new BlockPos(X + 90, Y, Z + 30), Direction.EAST, false), company, List.of());
        if (!placed.ok() || station == null || dock == null || tractor == null) {
            expect(source, "6: a Truck Station and a Tractor go down", false, placed.message().getString());
            return;
        }
        // it last stopped at the station, then fell out of the bottom of the world: the game deletes things down there
        tractor.lastStation = station.getBlockPos().immutable();
        tractor.cargo.setItem(0, new ItemStack(Items.IRON_INGOT, 9));
        tractor.setPos(tractor.getX(), level.getMinY() - 70, tractor.getZ());
        later(level, 5, () -> {
            boolean kept = !tractor.isRemoved() && tractor.cargo.getItem(0).is(Items.IRON_INGOT);
            double off = Math.hypot(tractor.getX() - dock.x, tractor.getZ() - dock.z);
            expect(source, "6: a vehicle that falls out of the world comes back at its last Truck Station's dock, cargo and all",
                kept && off < 1.5 && tractor.getY() >= Y - 2, "removed " + tractor.isRemoved() + ", at " + tractor.blockPosition().toShortString() + ", dock "
                    + BlockPos.containing(dock).toShortString());
            tractor.discard();
            List<ItemStack> refund = BuildGun.controllerAt(level, sp.at(0, 1, 1)) instanceof ControllerBlockEntity c ? Buildings.dismantle(level, c) : List.of();
            Siftec.LOGGER.info("SIFTEC: selftest fixes took the station down ({} stacks)", refund.size());
        });
        // one that never stopped at a station goes to its company's HUB
        Company hubCompany = company(level, OTHER, STRANGER, "vehicular_transport");
        BlockPos terminal = new BlockPos(X + 100, Y, Z + 10);
        int claimed = Claims.claim(level, hubCompany, terminal, 0, false, 1);
        VehicleEntity lost = Vehicles.spawn(level, new Placement(tractorDef, new BlockPos(X + 50, Y, Z + 64), Direction.EAST, false), hubCompany, List.of());
        if (lost == null) return;
        lost.setPos(lost.getX(), level.getMinY() - 70, lost.getZ());
        lost.onBelowWorld();
        double off = Math.hypot(lost.getX() - (terminal.getX() + 0.5), lost.getZ() - (terminal.getZ() + 0.5));
        expect(source, "6: one that never stopped at a station comes back beside its company's HUB",
            claimed == 1 && !lost.isRemoved() && off < 1.5 && lost.getY() >= Y - 2, "claimed " + claimed + ", removed " + lost.isRemoved() + ", at "
                + lost.blockPosition().toShortString() + ", HUB at " + terminal.toShortString());
        lost.discard();
        Claims.release(level, terminal);
    }

    // ------------------------------------------------------------------------------------------------ 7: crash sites

    private static void connector(CommandSourceStack source, ServerLevel level) {
        BlockPos pod = new BlockPos(X + 60, Y, Z + 64);
        CrashSites.forgetConnector(level, pod);
        level.setBlock(pod, Blocks.IRON_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        boolean first = CrashSites.placeConnector(level, pod);
        BlockPos at = CrashSites.connectorSpot(level, pod);
        boolean there = at != null && !level.getBlockState(at).isAir();
        if (at != null) level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        boolean again = CrashSites.placeConnector(level, pod);
        boolean stillGone = at == null || level.getBlockState(at).isAir();
        expect(source, "7: a pod's wire connector is put down once; taken away, it is not put down again", first && there && !again && stillGone
            && CrashSites.connectorPlaced(level, pod), "first " + first + " at " + at(at) + ", again " + again + ", spot empty after " + stillGone);
        CrashSites.forgetConnector(level, pod);
        level.setBlock(pod, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    // ------------------------------------------------------------------------------------------------ 8: tank fluids

    private static void fluids(CommandSourceStack source, ServerLevel level) {
        Company company = company(level, COMPANY, MEMBER, "vehicular_transport");
        BuildingDef def = Buildings.def("truck_station");
        if (def == null) return;
        int[] want = {10, 2};
        Fluid[] fluids = {Fluids.WATER, Fluids.LAVA};
        long[] mb = {400 + 20, 2000 + 500};
        Item[] gives = {item("siftec:packaged_water"), Items.LAVA_BUCKET};
        for (int i = 0; i < 2; i++) {
            Placement p = new Placement(def, new BlockPos(X + 20 + 26 * i, Y, Z + 40), Direction.NORTH, false);
            Buildings.Check placed = Buildings.place(level, p, company, null, true);
            ControllerBlockEntity c = BuildGun.controllerAt(level, p.at(0, 1, 1));
            BlockPos tank = c == null ? null : BuildingLogic.cell(c, create("fluid_tank"));
            Storage<FluidVariant> storage = tank == null ? null : FluidStorage.SIDED.find(level, tank, null);
            if (!placed.ok() || c == null || storage == null) {
                expect(source, "8: a Truck Station with a tank goes down", false, placed.message().getString());
                continue;
            }
            long in;
            try (Transaction tx = Transaction.openOuter()) {
                in = storage.insert(FluidVariant.of(fluids[i]), mb[i] * (FluidConstants.BUCKET / 1000), tx);
                tx.commit();
            }
            List<ItemStack> back = Buildings.dismantle(level, c);
            int got = count(back, gives[i]);
            expect(source, "8: dismantled, its tank's " + BuiltInRegistries.FLUID.getKey(fluids[i]) + " comes back as " + BuiltInRegistries.ITEM.getKey(gives[i]),
                got == want[i], (in * 1000 / FluidConstants.BUCKET) + " mB in the tank, " + got + " back (" + want[i] + " wanted)");
        }
    }
}
