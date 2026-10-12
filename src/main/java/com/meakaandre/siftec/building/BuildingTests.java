package com.meakaandre.siftec.building;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.registry.ModItems;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Selftests for the building framework, run by the automated smoke test with the test building:
 * {@code /siftec selftest buildings} (template, placing, ports, turning and flipping, refusing blocked space and a
 * short inventory, nudging into free space, protection against hands and explosions, dismantling with refund, and a
 * building that breaks from its core block or loses its controller), and {@code /siftec selftest buildings restart}
 * (a building left standing is still whole, listed and protected after a restart, and dismantles with its refund).
 * {@code /siftec worldtest buildgun} and {@code ... buildgun check} set up and check the client's use of the gun.
 * Every check prints a line starting "SELFTEST buildings"; failures say FAIL.
 */
public final class BuildingTests {
    private static final int X = 3000, Y = 230, Z = 2000;
    private static final String COMPANY = "selftest_buildings";
    private static final String TEST = "test_building";

    private record Later(long at, Runnable run) {
    }

    private static final List<Later> LATER = new ArrayList<>();
    private static int passed, failed;

    private BuildingTests() {
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
                .then(Commands.literal("selftest").then(Commands.literal("buildings").executes(BuildingTests::run)
                    .then(Commands.literal("restart").executes(BuildingTests::restart))))
                .then(Commands.literal("worldtest").then(Commands.literal("buildgun").executes(BuildingTests::gunSetup)
                    .then(Commands.literal("check").executes(BuildingTests::gunCheck))))));
    }

    private static void later(ServerLevel level, int ticks, Runnable run) {
        LATER.add(new Later(level.getGameTime() + ticks, run));
    }

    private static void report(CommandSourceStack source, String text) {
        String line = "SELFTEST buildings " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        report(source, (ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    private static Company company(ServerLevel level) {
        CompanyData data = CompanyData.get(level.getServer());
        Company c = data.companies().get(COMPANY);
        if (c == null) {
            c = new Company();
            c.id = COMPANY;
            c.name = "Selftest Buildings";
            data.companies().put(COMPANY, c);
            Companies.save(level.getServer());
        }
        return c;
    }

    /** Loads (and keeps loaded) an area and lays a stone floor at {@code y - 1}, with air above. */
    static void platform(ServerLevel level, int x0, int z0, int x1, int z1, int y, int height) {
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++)
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                level.setBlock(pos.set(x, y - 1, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int h = y; h < y + height; h++) {
                    if (!level.getBlockState(pos.set(x, h, z)).isAir()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
    }

    private static SimpleContainer wallet(int sheets, int rods) {
        SimpleContainer c = new SimpleContainer(9);
        Item sheet = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse("create:iron_sheet"));
        if (sheets > 0) c.addItem(new ItemStack(sheet, sheets));
        if (rods > 0) c.addItem(new ItemStack(ModItems.PARTS.get("iron_rod"), rods));
        return c;
    }

    private static int count(List<ItemStack> stacks, String id) {
        int n = 0;
        for (ItemStack s : stacks) if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(id)) n += s.getCount();
        return n;
    }

    private static int countIn(SimpleContainer c, String id) {
        List<ItemStack> all = new ArrayList<>();
        for (int i = 0; i < c.getContainerSize(); i++) all.add(c.getItem(i));
        return count(all, id);
    }

    /** How many of the building's blocks stand as the template says. */
    private static int standing(ServerLevel level, Placement p) {
        BuildingTemplate t = BuildingTemplate.of(p.def());
        int n = 0;
        for (BuildingTemplate.Cell cell : t.members) if (level.getBlockState(p.at(cell)).getBlock() == cell.state().getBlock()) n++;
        return n;
    }

    private static BlockPos cellOf(Placement p, String block) {
        BuildingTemplate t = BuildingTemplate.of(p.def());
        for (BuildingTemplate.Cell cell : t.members) {
            if (net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(cell.state().getBlock()).toString().equals(block)) return p.at(cell);
        }
        return null;
    }

    private static BuildingTemplate.Cell portCell(BuildingTemplate t, PortBlock.Kind kind) {
        for (BuildingTemplate.Cell cell : t.members) {
            if (cell.state().getBlock() instanceof PortBlock && cell.state().getValue(PortBlock.KIND) == kind) return cell;
        }
        return null;
    }

    private static List<ItemStack> dropsNear(ServerLevel level, BlockPos at) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(4))) {
            out.add(e.getItem().copy());
            e.discard();
        }
        return out;
    }

    private static long amountIn(ServerLevel level, BlockPos pos, Item item) {
        Storage<ItemVariant> s = ItemStorage.SIDED.find(level, pos, null);
        if (s == null) return -1;
        long n = 0;
        for (StorageView<ItemVariant> v : s) if (!v.isResourceBlank() && v.getResource().isOf(item)) n += v.getAmount();
        return n;
    }

    // ------------------------------------------------------------------------------------------------ the test

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        BuildingTemplate.forget();
        platform(level, X - 24, Z - 24, X + 24, Z + 24, Y, 16);
        later(level, 40, () -> runSettled(source));
        report(source, "test site ready; the test runs in two seconds");
        return 1;
    }

    private static void runSettled(CommandSourceStack source) {
        ServerLevel level = source.getServer().overworld();
        passed = failed = 0;
        Company company = company(level);
        BuildingDef def = Buildings.def(TEST);
        expect(source, "test building is listed", def != null, String.valueOf(Buildings.all().size()) + " buildings");
        if (def == null) return;
        expect(source, "test building is a dev building in the Production tab", def.dev() && def.category() == Category.PRODUCTION
            && Buildings.of(Category.PRODUCTION, true).contains(def) && !Buildings.of(Category.PRODUCTION, false).contains(def), "");

        // the template
        BuildingTemplate t = BuildingTemplate.of(def);
        expect(source, "template reads", t != null, BuildingTemplate.problem(TEST));
        if (t == null) return;
        int ports = 0;
        for (BuildingTemplate.Cell c : t.members) if (c.state().getBlock() instanceof PortBlock) ports++;
        expect(source, "template shape", t.cells.size() == 45 && t.members.size() == 44 && ports == 2 && t.height == 5 && t.depth == 3
                && t.minX == -1 && t.maxX == 1 && t.controller.x() == 0 && t.controller.y() == 0 && t.controller.z() == 0,
            t.cells.size() + " cells, " + t.members.size() + " members, " + ports + " ports, " + (t.maxX - t.minX + 1) + " x " + t.height + " x " + t.depth);

        // placing, facing north, paid from an inventory
        Placement a = new Placement(def, new BlockPos(X, Y, Z), Direction.NORTH, false);
        SimpleContainer wallet = wallet(2, 5);
        Buildings.Check placed = Buildings.place(level, a, company, wallet, true);
        expect(source, "creative skips the cost", placed.ok() && countIn(wallet, "create:iron_sheet") == 2 && countIn(wallet, "siftec:iron_rod") == 5,
            placed.message().getString());
        ControllerBlockEntity ca = level.getBlockEntity(a.at(t.controller)) instanceof ControllerBlockEntity c ? c : null;
        if (ca != null) {
            Buildings.dismantle(level, ca);
        }
        Buildings.Check dev = Buildings.check(level, a, company, wallet(2, 4), false);
        expect(source, "a survival player cannot place a dev building", "siftec.build_gun.dev".equals(dev.reason), String.valueOf(dev.reason));
        // the rest runs as a survival player would, with the dev rule lifted for the test building
        placed = placeAsSurvival(level, a, company, wallet);
        expect(source, "places and takes the cost", placed.ok() && countIn(wallet, "create:iron_sheet") == 0 && countIn(wallet, "siftec:iron_rod") == 1,
            placed.message().getString() + "; left " + countIn(wallet, "create:iron_sheet") + " sheets, " + countIn(wallet, "siftec:iron_rod") + " rods");
        expect(source, "every block stands", standing(level, a) == t.members.size(), standing(level, a) + " of " + t.members.size());
        BlockPos controllerA = a.at(t.controller);
        ca = level.getBlockEntity(controllerA) instanceof ControllerBlockEntity c ? c : null;
        expect(source, "controller remembers the building", ca != null && TEST.equals(ca.buildingId) && a.anchor().equals(ca.anchor)
            && ca.facing == Direction.NORTH && !ca.flip && COMPANY.equals(ca.companyId) && count(ca.refund, "create:iron_sheet") == 2 && count(ca.refund, "siftec:iron_rod") == 4,
            ca == null ? "no controller" : ca.buildingId + " refund " + ca.refund.size() + " stacks");
        BuildingIndex index = BuildingIndex.get(level);
        expect(source, "every block is listed under the controller", index.count(level, controllerA) == t.members.size()
            && BuildingIndex.isMember(level, cellOf(a, "create:mechanical_press")), index.count(level, controllerA) + " listed");

        // ports: the input passes items to the basin, only on its outward face; the output gives them back
        BlockPos basin = cellOf(a, "create:basin");
        BuildingTemplate.Cell in = portCell(t, PortBlock.Kind.ITEM_IN), out = portCell(t, PortBlock.Kind.ITEM_OUT);
        BlockPos inPos = a.at(in), outPos = a.at(out);
        Direction inFace = a.orient(in.state().getValue(PortBlock.FACING)), outFace = a.orient(out.state().getValue(PortBlock.FACING));
        Storage<ItemVariant> inStorage = ItemStorage.SIDED.find(level, inPos, inFace);
        long took = 0;
        if (inStorage != null) {
            try (Transaction tx = Transaction.openOuter()) {
                took = inStorage.insert(ItemVariant.of(Items.IRON_INGOT), 3, tx);
                tx.commit();
            }
        }
        expect(source, "input port feeds the basin", inStorage != null && took == 3 && amountIn(level, basin, Items.IRON_INGOT) == 3,
            "took " + took + ", basin has " + amountIn(level, basin, Items.IRON_INGOT));
        expect(source, "ports only work on their outward face", ItemStorage.SIDED.find(level, inPos, inFace.getOpposite()) == null
            && ItemStorage.SIDED.find(level, inPos, Direction.UP) == null, "");
        Storage<ItemVariant> outStorage = ItemStorage.SIDED.find(level, outPos, outFace);
        long inserted = -1;
        if (outStorage != null) {
            try (Transaction tx = Transaction.openOuter()) {
                inserted = outStorage.insert(ItemVariant.of(Items.IRON_INGOT), 1, tx);
            }
        }
        expect(source, "output port takes nothing in", outStorage != null && inserted == 0, "inserted " + inserted);

        // protection: a hand (even through the event), an explosion
        FakePlayer fake = FakePlayer.get(level);
        BlockPos press = cellOf(a, "create:mechanical_press");
        boolean handAllowed = PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, fake, press, level.getBlockState(press), level.getBlockEntity(press));
        expect(source, "a hand cannot break the core block", !handAllowed, "");
        BlockPos side = a.at(1, 2, 1).relative(a.facing().getClockWise());
        level.explode(null, side.getX() + 0.5, side.getY() + 0.5, side.getZ() + 0.5, 4f, Level.ExplosionInteraction.TNT);
        expect(source, "an explosion leaves it whole", standing(level, a) == t.members.size(), standing(level, a) + " of " + t.members.size());
        dropsNear(level, side); // the floor the explosion broke

        // turned and flipped: everything lands where the placement says, ports facing the turned way
        Placement b = new Placement(def, new BlockPos(X + 12, Y, Z), Direction.EAST, true);
        Buildings.Check pb = placeAsSurvival(level, b, company, wallet(2, 4));
        BlockState inB = level.getBlockState(b.at(in));
        expect(source, "rotates and flips", pb.ok() && standing(level, b) == t.members.size() && inB.getBlock() instanceof PortBlock
                && inB.getValue(PortBlock.FACING) == Direction.WEST && level.getBlockState(b.at(t.controller)).getBlock() instanceof ControllerBlock,
            pb.message().getString() + "; in port faces " + (inB.getBlock() instanceof PortBlock ? inB.getValue(PortBlock.FACING) : "-"));
        BlockPos rightOfB = b.at(1, 1, 0);
        expect(source, "flipped: its right column is on the other side", rightOfB.equals(b.anchor().offset(0, 1, -1)), rightOfB.toShortString());

        // blocked space: a building overlapping A is refused and nothing is taken; nudged clear, it fits
        Placement c0 = new Placement(def, new BlockPos(X + 1, Y, Z), Direction.NORTH, false);
        SimpleContainer w2 = wallet(2, 4);
        Buildings.Check blocked = placeAsSurvival(level, c0, company, w2);
        expect(source, "refuses blocked space", !blocked.ok() && !blocked.blocked.isEmpty() && countIn(w2, "siftec:iron_rod") == 4
            && index.count(level, controllerA) == t.members.size(), blocked.blocked.size() + " in the way");
        Placement c1 = c0.moved(1, 0, 0);
        Buildings.Check stillBlocked = Buildings.check(level, c1, company, w2, false);
        Placement c2 = c1.moved(1, 0, 0);
        Buildings.Check nudged = placeAsSurvival(level, c2, company, w2);
        expect(source, "nudged a block at a time until clear, then places", !stillBlocked.ok() && nudged.ok() && standing(level, c2) == t.members.size()
            && c2.anchor().equals(c0.anchor().offset(2, 0, 0)), stillBlocked.blocked.size() + " in the way after one nudge; " + nudged.message().getString());
        // a single block inside the inside space is enough to refuse
        Placement d = new Placement(def, new BlockPos(X, Y, Z + 10), Direction.SOUTH, false);
        BlockPos inside = d.at(0, 2, 1);
        level.setBlock(inside, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        Buildings.Check insideBlocked = Buildings.check(level, d, company, wallet(2, 4), true);
        expect(source, "a block in the inside space is in the way", insideBlocked.blocked.size() == 1 && insideBlocked.blocked.getFirst().equals(inside), insideBlocked.blocked.size() + " in the way");
        level.setBlock(inside, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // and a short inventory
        SimpleContainer poor = wallet(1, 4);
        Buildings.Check missing = placeAsSurvival(level, d, company, poor);
        expect(source, "refuses without the full cost", !missing.ok() && missing.missing.size() == 1 && missing.missing.getFirst().count() == 1
            && countIn(poor, "create:iron_sheet") == 1, missing.message().getString());

        // dismantling: the refund and the basin's contents come back, nothing is left
        List<ItemStack> back = ca == null ? List.of() : Buildings.dismantle(level, ca);
        expect(source, "dismantles with refund", count(back, "create:iron_sheet") == 2 && count(back, "siftec:iron_rod") == 4 && count(back, "minecraft:iron_ingot") == 3,
            back.size() + " stacks: " + count(back, "create:iron_sheet") + " sheets, " + count(back, "siftec:iron_rod") + " rods, " + count(back, "minecraft:iron_ingot") + " ingots");
        expect(source, "dismantling leaves nothing", standing(level, a) == 0 && index.count(level, controllerA) == 0 && level.getBlockState(controllerA).isAir()
            && dropsNear(level, controllerA).isEmpty(), standing(level, a) + " blocks left, " + index.count(level, controllerA) + " listed");

        // breaking from the core block: the press goes (as a command or another mod might do it); the building follows
        Placement e = new Placement(def, new BlockPos(X - 12, Y, Z), Direction.WEST, false);
        placeAsSurvival(level, e, company, wallet(2, 4));
        BlockPos pressE = cellOf(e, "create:mechanical_press"), controllerE = e.at(t.controller);
        level.setBlock(pressE, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // and the controller of another one
        Placement f = new Placement(def, new BlockPos(X - 12, Y, Z + 12), Direction.NORTH, false);
        placeAsSurvival(level, f, company, wallet(2, 4));
        BlockPos controllerF = f.at(t.controller);
        level.setBlock(controllerF, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        later(level, 45, () -> {
            List<ItemStack> dropsE = dropsNear(level, controllerE);
            expect(source, "breaks with refund from its core block", standing(level, e) == 0 && index.count(level, controllerE) == 0
                    && count(dropsE, "create:iron_sheet") == 2 && count(dropsE, "siftec:iron_rod") == 4,
                standing(level, e) + " blocks left, dropped " + count(dropsE, "create:iron_sheet") + " sheets and " + count(dropsE, "siftec:iron_rod") + " rods");
            List<ItemStack> dropsF = dropsNear(level, controllerF);
            expect(source, "losing the controller takes the rest down with refund", standing(level, f) == 0 && index.count(level, controllerF) == 0
                    && count(dropsF, "create:iron_sheet") == 2 && count(dropsF, "siftec:iron_rod") == 4,
                standing(level, f) + " blocks left, dropped " + count(dropsF, "create:iron_sheet") + " sheets and " + count(dropsF, "siftec:iron_rod") + " rods");
            // B and C stay up for the restart test
            report(source, "result: " + passed + " passed, " + failed + " failed");
        });
    }

    /** Places as a survival player of the company would (cost taken, land checked); dev buildings are allowed here. */
    private static Buildings.Check placeAsSurvival(ServerLevel level, Placement p, Company company, SimpleContainer wallet) {
        Buildings.Check check = Buildings.check(level, p, company, wallet, false);
        if (check.reason != null && check.reason.equals("siftec.build_gun.dev")) check.reason = null;
        if (!check.ok()) return check;
        // the same path the gun takes, past the creative-only rule for the test building
        return Buildings.placeChecked(level, p, company, wallet, check);
    }

    // ------------------------------------------------------------------------------------------------ after a restart

    private static int restart(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        passed = failed = 0;
        BuildingDef def = Buildings.def(TEST);
        BuildingTemplate t = def == null ? null : BuildingTemplate.of(def);
        if (t == null) {
            expect(source, "restart: test building", false, "missing");
            return 1;
        }
        Placement b = new Placement(def, new BlockPos(X + 12, Y, Z), Direction.EAST, true);
        BlockPos controllerB = b.at(t.controller);
        level.getChunk(controllerB);
        ControllerBlockEntity cb = level.getBlockEntity(controllerB) instanceof ControllerBlockEntity c ? c : null;
        BuildingIndex index = BuildingIndex.get(level);
        expect(source, "restart: still whole and listed", standing(level, b) == t.members.size() && index.count(level, controllerB) == t.members.size()
            && cb != null && TEST.equals(cb.buildingId) && cb.facing == Direction.EAST && cb.flip, standing(level, b) + " standing, " + index.count(level, controllerB) + " listed");
        BlockPos basin = cellOf(b, "create:basin");
        boolean handAllowed = PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, FakePlayer.get(level), basin, level.getBlockState(basin), level.getBlockEntity(basin));
        expect(source, "restart: still protected", !handAllowed, "");
        List<ItemStack> back = cb == null ? List.of() : Buildings.dismantle(level, cb);
        expect(source, "restart: dismantles with refund", count(back, "create:iron_sheet") == 2 && count(back, "siftec:iron_rod") == 4 && standing(level, b) == 0,
            back.size() + " stacks back");
        Placement c2 = new Placement(def, new BlockPos(X + 3, Y, Z), Direction.NORTH, false);
        if (level.getBlockEntity(c2.at(t.controller)) instanceof ControllerBlockEntity cc) Buildings.dismantle(level, cc);
        report(source, "restart result: " + passed + " passed, " + failed + " failed");
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ the client's gun

    private static final int GX = 1100, GY = 181, GZ = 1000;

    /** A floor for the client test, the player on it looking north and down at it, in creative with a Build Gun. */
    private static int gunSetup(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        platform(level, GX - 10, GZ - 16, GX + 10, GZ + 6, GY, 12);
        BuildGun.USES.clear();
        for (var player : source.getServer().getPlayerList().getPlayers()) {
            player.teleportTo(level, GX + 0.5, GY, GZ + 0.5, java.util.Set.of(), 180f, 35f, true);
            player.getInventory().clearContent();
            player.getInventory().setItem(player.getInventory().getSelectedSlot(), new ItemStack(ModItems.BUILD_GUN.get()));
        }
        report(source, "gun test ready");
        return 1;
    }

    /**
     * What the client did with the gun: built once where it aimed, took that down, then built again after two
     * nudges forward (W), which must be two blocks further north, and took that down too.
     */
    private static int gunCheck(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        passed = failed = 0;
        List<String> uses = new ArrayList<>(BuildGun.USES);
        report(source, "gun uses: " + uses);
        List<BlockPos> built = new ArrayList<>();
        int dismantled = 0;
        for (String u : uses) {
            String[] w = u.split(" ");
            if (w[0].equals("placed") && w.length >= 5) built.add(new BlockPos(Integer.parseInt(w[2]), Integer.parseInt(w[3]), Integer.parseInt(w[4])));
            if (w[0].equals("dismantled")) dismantled++;
        }
        expect(source, "client: the gun built twice and dismantled twice", built.size() == 2 && dismantled == 2, built.size() + " built, " + dismantled + " dismantled");
        if (built.size() == 2) {
            expect(source, "client: two nudges forward moved it two blocks north", built.get(1).equals(built.get(0).offset(0, 0, -2)),
                built.get(0).toShortString() + " then " + built.get(1).toShortString());
        }
        report(source, "client result: " + passed + " passed, " + failed + " failed");
        return 1;
    }
}
