package com.meakaandre.siftec.building;

import com.meakaandre.siftec.TestQueue;
import com.meakaandre.siftec.TestReport;
import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.command.LogisticsTests;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.HubBlock;
import com.meakaandre.siftec.hub.HubBlockEntity;
import com.meakaandre.siftec.node.NodeLook;
import com.meakaandre.siftec.node.NodeRegrowth;
import com.meakaandre.siftec.node.NodeSites;
import com.meakaandre.siftec.node.NodeType;
import com.meakaandre.siftec.node.Purity;
import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.power.PowerTests;
import com.meakaandre.siftec.prefab.HubBurnerBlockEntity;
import com.meakaandre.siftec.prefab.Prefabs;
import com.meakaandre.siftec.prefab.Templates;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.registry.ModItems;
import com.meakaandre.siftec.tweak.SelfMovingBelts;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.api.behaviour.BlockEntityBehaviour;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.zurrtum.create.content.kinetics.belt.item.BeltConnectorItem;
import com.zurrtum.create.content.kinetics.crank.HandCrankBlockEntity;
import com.zurrtum.create.content.logistics.depot.DepotBlockEntity;
import com.zurrtum.create.content.processing.basin.BasinBlockEntity;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import org.antarcticgardens.cna.content.electricity.connector.AbstractElectricalConnector;
import org.antarcticgardens.cna.content.motor.MotorBlockEntity;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Selftests for the basic production buildings, run by the smoke test on a test world: {@code /siftec selftest buildings_a}
 * puts each one down (paid from an inventory, as the Build Gun does), feeds it by Create belts at its ports, and checks
 * after a minute of game time that it made its recipe at Satisfactory's rate (Constructor: Iron Plate, Smelter: Iron
 * Ingot, Miner Mk.1 to Mk.3 on normal, impure and pure nodes, the Portable Miner dropping its ore), plus the Storage
 * Containers, the HUB's cost and refund, its Build Gun and its hand-cranked Craft Station. {@code ... restart} checks
 * that the buildings left standing kept their settings and still run. Every check prints "SELFTEST buildings_a";
 * failures say FAIL.
 */
public final class BuildingsATests {
    private static final int X = 3600, Y = 230, Z = 2400;
    private static final String COMPANY = "selftest_wp5";
    /** Ticks the buildings get to settle before the rates are measured, and how long they are measured over. */
    private static final int WARMUP = 300, MINUTE = 1200;

    private static final TestQueue LATER = new TestQueue();
    private static final List<Runnable> EVERY_TICK = new ArrayList<>();
    private static final TestReport REPORT = new TestReport("SELFTEST buildings_a ");

    private BuildingsATests() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (Runnable r : new ArrayList<>(EVERY_TICK)) r.run();
            LATER.runDue(server.overworld().getGameTime());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LATER.clear();
            EVERY_TICK.clear();
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("buildings_a").executes(BuildingsATests::run)
                    .then(Commands.literal("restart").executes(BuildingsATests::restart))))
                .then(Commands.literal("worldtest").then(Commands.literal("buildings_a").then(Commands.literal("view")
                    .then(Commands.argument("n", IntegerArgumentType.integer(0, 3))
                        .executes(c -> view(c, IntegerArgumentType.getInteger(c, "n")))))))));
    }

    private static void later(ServerLevel level, int ticks, Runnable run) {
        LATER.add(level.getGameTime() + ticks, run);
    }

    private static void report(CommandSourceStack source, String text) {
        REPORT.report(source, text);
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        REPORT.expect(source, what, ok, detail);
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
    }

    private static Company company(ServerLevel level) {
        CompanyData data = CompanyData.get(level.getServer());
        Company c = data.companies().get(COMPANY);
        if (c == null) {
            c = new Company();
            c.id = COMPANY;
            c.name = "Selftest Buildings A";
            data.companies().put(COMPANY, c);
        }
        for (int i = 1; i <= 6; i++) c.done.add("hub_upgrade_" + i);
        for (String m : new String[]{"logistics_mk3", "advanced_steel_production", "leading_edge_production", "part_assembly"}) c.done.add(m);
        Companies.save(level.getServer());
        return c;
    }

    /** An inventory holding exactly a building's cost (a tag cost is paid in its first item). */
    private static SimpleContainer wallet(BuildingDef def) {
        SimpleContainer c = new SimpleContainer(27);
        for (var cost : def.cost()) {
            ItemStack icon = cost.isTag() ? new ItemStack(Items.STONE) : cost.icon();
            int left = cost.count();
            while (left > 0) {
                int n = Math.min(left, icon.getMaxStackSize());
                c.addItem(icon.copyWithCount(n));
                left -= n;
            }
        }
        return c;
    }

    private static boolean empty(SimpleContainer c) {
        for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty()) return false;
        return true;
    }

    private static @Nullable ControllerBlockEntity controller(ServerLevel level, Placement p) {
        BuildingTemplate t = BuildingTemplate.of(p.def());
        return t != null && level.getBlockEntity(p.at(t.controller)) instanceof ControllerBlockEntity c ? c : null;
    }

    private static long count(ServerLevel level, @Nullable BlockPos pos, Item item) {
        if (pos == null) return 0;
        // a Basin's item storage only shows its output; its inventory holds both
        if (level.getBlockEntity(pos) instanceof BasinBlockEntity basin) {
            long n = 0;
            for (int i = 0; i < basin.itemCapability.getContainerSize(); i++) if (basin.itemCapability.getItem(i).is(item)) n += basin.itemCapability.getItem(i).getCount();
            return n;
        }
        Storage<ItemVariant> s = ItemStorage.SIDED.find(level, pos, null);
        long n = 0;
        if (s != null) for (StorageView<ItemVariant> v : s) if (!v.isResourceBlank() && v.getResource().isOf(item)) n += v.getAmount();
        if (level.getBlockEntity(pos) instanceof DepotBlockEntity depot && depot.getHeldItem() != null && depot.getHeldItem().stack.is(item)) {
            n = Math.max(n, depot.getHeldItem().stack.getCount());
        }
        return n;
    }

    /** Items of a kind on a belt (every segment's inventory is its controller's). */
    private static long onBelt(ServerLevel level, BlockPos anySegment, Item item) {
        if (!(level.getBlockEntity(anySegment) instanceof BeltBlockEntity belt) || belt.getInventory() == null) return 0;
        long n = 0;
        for (var t : belt.getInventory().getTransportedItems()) if (t.stack.is(item)) n += t.stack.getCount();
        return n;
    }

    // ------------------------------------------------------------------------------------------------ belts

    /**
     * A Create belt from {@code from} to {@code to} (in a straight line on the ground), carrying items from {@code from}
     * toward {@code to} at its owning company's speed (80 items a minute or more).
     */
    private static void belt(ServerLevel level, BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        Direction along = dx != 0 ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        Direction.Axis shaft = along.getClockWise().getAxis();
        BlockState shaftState = block("create:shaft").defaultBlockState().setValue(BlockStateProperties.AXIS, shaft);
        level.setBlock(from, shaftState, Block.UPDATE_ALL);
        level.setBlock(to, shaftState, Block.UPDATE_ALL);
        BeltConnectorItem.createBelts(level, from, to);
        // belts move by themselves at their company's speed (no shaft drives one): owned by a company fast enough
        String company = LogisticsTests.beltCompany(level, (int) Math.ceil(32 * SelfMovingBelts.ITEMS_PER_MINUTE_PER_RPM));
        int length = Math.abs(dx) + Math.abs(dz);
        for (int i = 0; i <= length; i++) {
            BlockEntity be = level.getBlockEntity(from.relative(along, i));
            if (be != null) be.setAttached(Ownership.OWNER, company);
            if (be instanceof BeltBlockEntity b) SelfMovingBelts.recheck(b);
        }
    }

    private static long pushed(ServerLevel level, BlockPos port) {
        return level.getBlockEntity(port) instanceof PortBlockEntity p ? p.pushed : -1;
    }

    private static int ground(ServerLevel level, BlockPos near, Item item) {
        int n = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(near).inflate(4))) if (e.getItem().is(item)) n += e.getItem().getCount();
        return n;
    }

    /** A belt's speed, the way it runs and what it carries, for the report. */
    private static String beltState(ServerLevel level, BlockPos pos, Item item) {
        if (!(level.getBlockEntity(pos) instanceof BeltBlockEntity b)) return "no belt (" + level.getBlockState(pos).getBlock().getClass().getSimpleName() + ")";
        return b.getSpeed() + " RPM toward " + b.getMovementFacing() + ", " + onBelt(level, pos, item) + " on it";
    }

    /** Puts one item on a belt's first block, as a funnel or chute would; false if it has no room there. */
    private static boolean feed(ServerLevel level, BlockPos beltStart, Direction along, ItemStack one) {
        DirectBeltInputBehaviour in = BlockEntityBehaviour.get(level, beltStart, DirectBeltInputBehaviour.TYPE);
        if (in == null || !in.canInsertFromSide(along)) return false;
        return in.handleInsertion(one.copy(), along, false).isEmpty();
    }

    /** Feeds a belt one item every few ticks, up to a total. */
    private static void feeder(ServerLevel level, BlockPos beltStart, Direction along, ItemStack one, int every, int total) {
        int[] fed = {0};
        long[] tick = {0};
        EVERY_TICK.add(new Runnable() {
            @Override
            public void run() {
                if (fed[0] >= total) {
                    EVERY_TICK.remove(this);
                    return;
                }
                if (tick[0]++ % every != 0) return;
                if (feed(level, beltStart, along, one)) fed[0]++;
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ the test

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        BuildingTemplate.forget();
        BuildingTests.platform(level, X - 20, Z - 30, X + 140, Z + 30, Y, 14);
        later(level, 40, () -> runSettled(source));
        report(source, "test site ready; the test runs in two seconds");
        return 1;
    }

    private static Placement at(String id, int x, int z) {
        return new Placement(Buildings.def(id), new BlockPos(x, Y, z), Direction.NORTH, false);
    }

    /** Puts a building down as a survival player of the company would, paid from a wallet that holds exactly its cost. */
    private static Buildings.Check put(ServerLevel level, CommandSourceStack source, Company company, Placement p) {
        SimpleContainer w = wallet(p.def());
        Buildings.Check check = Buildings.place(level, p, company, w, false);
        expect(source, p.def().id() + " goes down and its cost is paid", check.ok() && empty(w), check.message().getString());
        return check;
    }

    /** A hand-made node at a spot: the real ore block of the look's type, kept as a node of that purity by its position, on its patch stone. */
    private static BlockPos node(ServerLevel level, NodeType type, Purity purity, int x, int z) {
        return node(level, type, purity, x, Y, z);
    }

    private static BlockPos node(ServerLevel level, NodeType type, Purity purity, int x, int y, int z) {
        NodeLook look = NodeLook.of(type);
        BlockPos at = new BlockPos(x, y, z);
        level.setBlock(at.below(), look.patchStone(), Block.UPDATE_ALL);
        if (look.fluid()) level.setBlock(at, ModBlocks.NODES.get(look.id()).get().defaultBlockState().setValue(NodeBlock.PURITY, purity), Block.UPDATE_ALL);
        else NodeSites.put(level, at, look, purity);
        return at;
    }

    private static void runSettled(CommandSourceStack source) {
        ServerLevel level = source.getServer().overworld();
        REPORT.reset();
        Company company = company(level);

        // ---- the list: every building of this set, with its tab, unlock, SU and a template that reads
        String[][] want = {{"portable_miner", "PRODUCTION", "hub_upgrade_1", "portable_miner"}, {"miner_mk1", "PRODUCTION", "hub_upgrade_5", "miner"},
            {"miner_mk2", "PRODUCTION", "advanced_steel_production", "miner"}, {"miner_mk3", "PRODUCTION", "leading_edge_production", "miner"},
            {"smelter", "PRODUCTION", "hub_upgrade_2", "smelter"}, {"constructor", "PRODUCTION", "hub_upgrade_3", "basin"},
            {"storage_container", "LOGISTICS", "hub_upgrade_5", "storage"}, {"industrial_storage_container", "LOGISTICS", "logistics_mk3", "storage"}};
        for (String[] w : want) {
            BuildingDef def = Buildings.def(w[0]);
            BuildingTemplate t = def == null ? null : BuildingTemplate.of(def);
            expect(source, w[0] + " is listed with its tab, unlock and logic, and its template reads", def != null && t != null && def.category().name().equals(w[1])
                    && def.unlock().equals(w[2]) && def.logic().id().equals(w[3]) && !def.dev(),
                def == null ? "missing" : def.category() + " " + def.unlock() + " " + def.logic() + " su " + def.su() + (t == null ? " no template: " + BuildingTemplate.problem(w[0]) : ""));
        }
        expect(source, "SU follow Satisfactory's MW (1 MW = 204.8 SU)", Buildings.def("constructor").su() == 819 && Buildings.def("smelter").su() == 819
            && Buildings.def("miner_mk1").su() == 1024 && Buildings.def("miner_mk2").su() == 3072 && Buildings.def("miner_mk3").su() == 9216, "");
        Buildings.Check locked = Buildings.check(level, at("constructor", X + 130, Z), new Company(), wallet(Buildings.def("constructor")), false);
        expect(source, "a company without the milestone cannot build it", "siftec.build_gun.locked".equals(locked.reason), String.valueOf(locked.reason));

        // ---- Constructor making Iron Plate (3 Iron Ingot -> 2 Iron Plate, 6 s): fed by a belt at the back, out on a belt at the front
        Placement con = at("constructor", X, Z);
        put(level, source, company, con);
        ControllerBlockEntity cc = controller(level, con);
        if (cc != null) BuildingLogic.setFilter(level, cc, new ItemStack(item("create:iron_sheet")));
        // the input port is in the back row of the pad: the belt comes in from five blocks behind it
        int conBack = BuildingTemplate.of(con.def()).depth - 1;
        BlockPos conIn = con.at(0, 0, conBack + 5), conInEnd = con.at(0, 0, conBack + 1), conOut = con.at(0, 0, -1), conOutEnd = con.at(0, 0, -6);
        belt(level, conIn, conInEnd);
        belt(level, conOut, conOutEnd);
        // a belt ends into a Basin as into a Depot, and a Basin holds nine stacks
        level.setBlock(con.at(0, 0, -7), block("create:basin").defaultBlockState(), Block.UPDATE_ALL);
        feeder(level, conIn, Direction.SOUTH, new ItemStack(Items.IRON_INGOT), 6, 120);
        BlockPos basin = BuildingLogic.cell(cc, block("create:basin"));
        expect(source, "the Constructor's Basin takes its filter", basin != null && level.getBlockEntity(basin) instanceof BasinBlockEntity b
            && b.getFilter().getFilter().is(item("create:iron_sheet")), "");

        // ---- Smelter making Iron Ingot (1 Raw Iron -> 1 Iron Ingot, 2 s)
        Placement sm = at("smelter", X + 20, Z);
        put(level, source, company, sm);
        int smBack = BuildingTemplate.of(sm.def()).depth - 1;
        BlockPos smIn = sm.at(0, 0, smBack + 5), smInEnd = sm.at(0, 0, smBack + 1), smOut = sm.at(0, 0, -1), smOutEnd = sm.at(0, 0, -6);
        belt(level, smIn, smInEnd);
        belt(level, smOut, smOutEnd);
        level.setBlock(sm.at(0, 0, -7), block("create:basin").defaultBlockState(), Block.UPDATE_ALL);
        feeder(level, smIn, Direction.SOUTH, new ItemStack(Items.RAW_IRON), 4, 200);

        // ---- miners on hand-made nodes, each aimed a little off and snapped onto its node; their ore goes into a chest
        // id, node, purity, and the items a break gives on average: Mk.1 none extra, Mk.2 Fortune III (2.2 on average by
        // vanilla's ore formula), Mk.3 Fortune V (3.14) on copper ore, which drops 2 to 5 raw copper as in vanilla (11 on average)
        Object[][] miners = {{"miner_mk1", NodeType.IRON, Purity.NORMAL, 1f, 1f}, {"miner_mk2", NodeType.IRON, Purity.IMPURE, 1.5f, 3f},
            {"miner_mk3", NodeType.COPPER, Purity.PURE, 8f, 14f}};
        List<Placement> minerSpots = new ArrayList<>();
        for (int i = 0; i < miners.length; i++) {
            int x = X + 40 + 15 * i;
            BlockPos n = node(level, (NodeType) miners[i][1], (Purity) miners[i][2], x, Z);
            Placement rough = at((String) miners[i][0], x + 1, Z + 3);
            Placement snapped = BuildingNodes.snapToNode(level, rough);
            int[] cell = snapped.def().nodeCell();
            expect(source, miners[i][0] + " snaps onto the node it is aimed near", snapped.at(cell[0], cell[1], cell[2]).equals(n), snapped.anchor().toShortString());
            put(level, source, company, snapped);
            level.setBlock(snapped.at(0, 0, -1), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            minerSpots.add(snapped);
        }
        // a node building off any node is refused
        Buildings.Check offNode = Buildings.check(level, at("miner_mk1", X + 130, Z + 10), company, wallet(Buildings.def("miner_mk1")), false);
        expect(source, "a miner off any node is refused", "siftec.build_gun.no_node".equals(offNode.reason), String.valueOf(offNode.reason));

        // ---- Portable Miner on a normal copper node: it breaks the node about 20 times a minute, the ore dropped in front
        BlockPos pmNode = node(level, NodeType.IRON, Purity.NORMAL, X + 90, Z);
        Placement pm = BuildingNodes.snapToNode(level, at("portable_miner", X + 90, Z + 2));
        put(level, source, company, pm);

        // ---- every miner really mines: count each time its node's ore block goes from grown to plain air, seen from outside
        List<BlockPos> watched = new ArrayList<>();
        for (Placement p : minerSpots) {
            int[] cell = p.def().nodeCell();
            watched.add(p.at(cell[0], cell[1], cell[2]));
        }
        watched.add(pmNode);
        long[] depletions = new long[watched.size()];
        boolean[] wasGrown = new boolean[watched.size()];
        boolean[] counting = {false};
        EVERY_TICK.add(() -> {
            for (int i = 0; i < watched.size(); i++) {
                var site = NodeSites.at(level, watched.get(i));
                BlockState now = level.getBlockState(watched.get(i));
                boolean grown = site != null && site.look.isNodeBlock(now);
                if (counting[0] && wasGrown[i] && !grown && now.isAir()) depletions[i]++;
                wasGrown[i] = grown;
            }
        });

        // ---- Storage Containers: a belt into the back of one; its front port fills a chest
        Placement st = at("storage_container", X + 105, Z);
        put(level, source, company, st);
        BlockPos stIn = st.at(0, 0, 9), stInEnd = st.at(0, 0, 5);
        belt(level, stIn, stInEnd);
        BlockPos stChest = st.at(0, 0, -1);
        level.setBlock(stChest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        feeder(level, stIn, Direction.SOUTH, new ItemStack(Items.COBBLESTONE), 3, 30);
        Placement ist = at("industrial_storage_container", X + 115, Z);
        put(level, source, company, ist);
        ControllerBlockEntity istc = controller(level, ist);

        // ---- power: the powered buildings stand still until they are on a grid with power. An Alien Power
        // Augmenter (500 MW, no fuel) on a Power Pole Mk.3 gives it to them all.
        ControllerBlockEntity aug = PowerTests.build(level, source, "alien_power_augmenter", new BlockPos(X + 60, Y, Z + 30), Direction.NORTH);
        ControllerBlockEntity pole = PowerTests.build(level, source, "power_pole_mk3", new BlockPos(X + 60, Y, Z + 22), Direction.NORTH);
        var poleConnector = PowerTests.connector(level, pole);
        List<ControllerBlockEntity> powered = new ArrayList<>();
        if (aug != null) powered.add(aug);
        powered.add(cc);
        powered.add(controller(level, sm));
        for (Placement p : minerSpots) powered.add(controller(level, p));
        int wired = 0;
        for (ControllerBlockEntity c : powered) if (c != null && PowerTests.wire(PowerTests.connector(level, c), poleConnector)) wired++;
        expect(source, "power: the Augmenter, Constructor, Smelter and miners are wired to a Power Pole Mk.3", wired == powered.size(), wired + " of " + powered.size());
        // (an idle Constructor draws next to nothing and its motor stands, as Satisfactory's idle machines do: it is
        // checked once its belt has brought it ingots)
        later(level, 200, () -> expect(source, "power: the Constructor's New Age motor runs on the grid at 64 RPM, giving its 4 MW (819 SU)",
            cc != null && cc.powered && (cc.idle || motorTurns(level, cc)), cc == null ? "no controller" : BuildingLogic.status(level, cc).getString()));

        // ---- the HUB: costs 20 Raw Iron from the player, gives the Build Gun, gives the iron back when picked up
        hubTest(source, level, company);

        // ---- every ten seconds, where the belt-fed buildings' items are (for working out a failure)
        for (int t = 100; t <= WARMUP + MINUTE; t += 200) {
            final int at = t;
            later(level, t, () -> report(source, "at " + at + ": constructor " + made(level, con) + " made, basin " + count(level, basin, Items.IRON_INGOT) + " ingots "
                + count(level, basin, item("create:iron_sheet")) + " plates, in belt " + beltState(level, conIn, Items.IRON_INGOT) + ", out port pushed " + pushed(level, con.at(0, 0, 0))
                + ", out belt " + beltState(level, conOut, item("create:iron_sheet")) + ", end " + count(level, con.at(0, 0, -7), item("create:iron_sheet"))
                + ", on the ground " + ground(level, conOutEnd, item("create:iron_sheet")) + " | smelter " + made(level, sm) + " made, out port pushed "
                + pushed(level, sm.at(0, 0, 0)) + ", out belt " + beltState(level, smOut, Items.IRON_INGOT) + ", end " + count(level, sm.at(0, 0, -7), Items.IRON_INGOT)
                + ", on the ground " + ground(level, smOutEnd, Items.IRON_INGOT) + " | storage in belt " + beltState(level, stIn, Items.COBBLESTONE)));
        }

        // ---- measure: what each building has made over a minute once it has settled
        long[] start = new long[8], startItems = new long[4];
        later(level, WARMUP, () -> {
            start[0] = made(level, con);
            start[1] = made(level, sm);
            for (int i = 0; i < 3; i++) start[2 + i] = made(level, minerSpots.get(i));
            start[5] = made(level, pm);
            for (int i = 0; i < 3; i++) startItems[i] = mined(level, minerSpots.get(i));
            startItems[3] = mined(level, pm);
            counting[0] = true;
            report(source, "measuring from " + Arrays.toString(start));
            report(source, "belts: constructor in " + beltState(level, conIn, Items.IRON_INGOT) + ", out " + beltState(level, conOut, item("create:iron_sheet"))
                + "; basin " + count(level, basin, Items.IRON_INGOT) + " ingots " + count(level, basin, item("create:iron_sheet")) + " plates; smelter in "
                + beltState(level, smIn, Items.RAW_IRON) + ", out " + beltState(level, smOut, Items.IRON_INGOT) + ", controller in "
                + (controller(level, sm) == null ? "-" : controller(level, sm).input.getItem(0) + " out " + controller(level, sm).output.getItem(0))
                + "; storage in " + beltState(level, stIn, Items.COBBLESTONE));
        });
        later(level, WARMUP + MINUTE, () -> {
            long crafts = made(level, con) - start[0];
            expect(source, "Constructor: 10 Iron Plate recipes a minute (6 s each)", crafts >= 9 && crafts <= 11, crafts + " recipes");
            long plates = count(level, con.at(0, 0, -7), item("create:iron_sheet")) + onBelt(level, conOut, item("create:iron_sheet"));
            expect(source, "Constructor: its plates come out on the belt at the front", plates >= 16, plates + " plates on the belt and in the Basin at its end");
            long ingots = made(level, sm) - start[1];
            expect(source, "Smelter: 30 Iron Ingot a minute (2 s each)", ingots >= 26 && ingots <= 34, ingots + " ingots");
            long chest = count(level, sm.at(0, 0, -7), Items.IRON_INGOT) + onBelt(level, smOut, Items.IRON_INGOT);
            expect(source, "Smelter: its ingots come out on the belt at the front", chest >= 20, chest + " on the belt and in the Basin at its end");
            counting[0] = false;
            for (int i = 0; i < 3; i++) {
                Placement p = minerSpots.get(i);
                Purity purity = (Purity) miners[i][2];
                long breaks = made(level, p) - start[2 + i], items = mined(level, p) - startItems[i];
                BuildingDef d = p.def();
                // a break needs the node grown back first: purity caps the breaks, the Mk's break time slows them a little
                int regrow = NodeRegrowth.ticks(purity);
                long most = 1200 / regrow + 1, least = (long) (1200f / (regrow + d.breakTicks() + 4) * 0.85f);
                expect(source, miners[i][0] + " on a " + purity + " node really breaks it, " + least + " to " + most + " times a minute (purity regrows it every "
                    + regrow + " ticks, the Mk breaks it in " + d.breakTicks() + ")", breaks >= least && breaks <= most && Math.abs(breaks - depletions[i]) <= 1,
                    breaks + " breaks, " + depletions[i] + " depletions seen on the node");
                float per = breaks == 0 ? 0 : items / (float) breaks;
                float lo = (Float) miners[i][3], hi = (Float) miners[i][4];
                expect(source, miners[i][0] + ": its Fortune " + d.fortune() + " gives " + lo + " to " + hi + " items a break, and nothing without a break",
                    breaks > 0 && per >= lo && per <= hi, items + " items from " + breaks + " breaks (" + String.format(Locale.ROOT, "%.2f", per) + " a break)");
                ControllerBlockEntity mc = controller(level, p);
                BlockState nodeNow = mc == null || mc.node == null ? null : level.getBlockState(mc.node);
                Block oreBlock = ((NodeType) miners[i][1]) == NodeType.IRON ? Blocks.IRON_ORE : Blocks.COPPER_ORE;
                expect(source, miners[i][0] + ": the node is still there (its ore block " + BuiltInRegistries.BLOCK.getKey(oreBlock) + ", or air growing back) and its ore reaches the chest",
                    nodeNow != null && (nodeNow.is(oreBlock) || nodeNow.isAir()) && NodeSites.isSite(level, mc.node)
                        && count(level, p.at(0, 0, -1), ((NodeType) miners[i][1]) == NodeType.IRON ? Items.RAW_IRON : Items.RAW_COPPER) > 0,
                    mc == null ? "no controller" : String.valueOf(nodeNow));
            }
            long dug = made(level, pm) - start[5], dugItems = mined(level, pm) - startItems[3];
            expect(source, "Portable Miner on a normal node: breaks it 16 to 21 times a minute (40 ticks each, after 20 to regrow), one ore each",
                dug >= 16 && dug <= 21 && dugItems == dug && Math.abs(dug - depletions[3]) <= 1, dug + " breaks, " + dugItems + " items, " + depletions[3] + " depletions seen");
            int lying = 0;
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(pm.at(0, 0, -1)).inflate(1.5))) if (e.getItem().is(Items.RAW_IRON)) lying += e.getItem().getCount();
            expect(source, "Portable Miner: the ore lies on the ground in front", lying >= 16, lying + " lying there");
            long stored = count(level, stChest, Items.COBBLESTONE) + count(level, BuildingLogic.cell(controller(level, st), block("create:item_vault")), Items.COBBLESTONE);
            expect(source, "Storage Container: the belt fills it and its front port empties it into a chest", stored == 30 && count(level, stChest, Items.COBBLESTONE) > 0,
                count(level, stChest, Items.COBBLESTONE) + " in the chest of " + stored);
            int slots = 0;
            BlockPos vault = istc == null ? null : BuildingLogic.cell(istc, block("create:item_vault"));
            Storage<ItemVariant> vs = vault == null ? null : ItemStorage.SIDED.find(level, vault, null);
            if (vs != null) for (StorageView<ItemVariant> ignored : vs) slots++;
            expect(source, "Industrial Storage Container: two vault blocks hold twice a Storage Container", slots == 40, slots + " slots");
            expect(source, "a building's status line reads", cc != null && !BuildingLogic.status(level, cc).getString().isEmpty(), cc == null ? "" : BuildingLogic.status(level, cc).getString());
            // dismantling gives back the cost and what is inside
            ControllerBlockEntity smc = controller(level, sm);
            List<ItemStack> back = smc == null ? List.of() : Buildings.dismantle(level, smc);
            int rods = 0;
            for (ItemStack s : back) if (s.is(item("siftec:iron_rod"))) rods += s.getCount();
            expect(source, "Smelter dismantles with its cost and contents", rods == 5, back.size() + " stacks back, " + rods + " rods");
            report(source, "result: " + REPORT.totals());
        });
    }

    /** True if a building's first New Age motor turns at its 64 RPM. */
    private static boolean motorTurns(ServerLevel level, ControllerBlockEntity c) {
        List<BlockPos> motors = BuildingPower.motors(c);
        return !motors.isEmpty() && level.getBlockEntity(motors.getFirst()) instanceof KineticBlockEntity k
            && Math.abs(k.getSpeed()) == BuildingLogic.MOTOR_RPM;
    }

    private static long mined(ServerLevel level, Placement p) {
        ControllerBlockEntity c = controller(level, p);
        return c == null ? 0 : c.mined;
    }

    private static long made(ServerLevel level, Placement p) {
        ControllerBlockEntity c = controller(level, p);
        return c == null ? -1 : c.made;
    }

    // ------------------------------------------------------------------------------------------------ the HUB

    private static void hubTest(CommandSourceStack source, ServerLevel level, Company company) {
        Prefabs.Spot spot = new Prefabs.Spot(Prefabs.Kind.HUB, new BlockPos(X + 40, Y, Z - 30), Direction.SOUTH);
        BuildingTests.platform(level, X + 20, Z - 60, X + 60, Z - 20, Y, 30);
        FakePlayer fake = FakePlayer.get(level);
        // the smoke server's default is creative, which would skip the cost
        fake.setGameMode(GameType.SURVIVAL);
        fake.getInventory().clearContent();
        fake.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.BUILD_GUN.get()));
        fake.getInventory().setItem(1, new ItemStack(Items.RAW_IRON, 19));
        // the HUB is a Build Gun building like any other: listed in the Special tab, put down with the gun
        BuildingDef hubDef = Buildings.def("hub");
        expect(source, "HUB: a Build Gun building in the Special tab, unlocked from the start, costing 20 Raw Iron, and there is no HUB item",
            hubDef != null && hubDef.hub() && Buildings.of(Category.SPECIAL, false).contains(hubDef) && hubDef.unlock().isEmpty()
                && hubDef.cost().size() == 1 && hubDef.cost().getFirst().count() == 20 && ModBlocks.HUB.get().asItem() == Items.AIR,
            hubDef == null ? "not listed" : hubDef.cost() + ", item " + ModBlocks.HUB.get().asItem());
        if (hubDef == null) return;
        Placement at = new Placement(hubDef, spot.anchor(), spot.facing(), false);
        // the gun only builds at a hologram near the player: the fake player stands at the HUB's front edge
        BlockPos stand = spot.anchor().relative(spot.facing().getOpposite(), 3);
        fake.setPos(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
        BuildGun.select(fake, "hub", false, at);
        Prefabs.Check verdict = BuildGun.hubCheck(fake, at, company);
        BuildGun.act(fake, company);
        boolean none = !(level.getBlockEntity(spot.terminal()) instanceof HubBlockEntity);
        expect(source, "HUB: the Build Gun refuses it without 20 Raw Iron", none && "siftec.prefab.cost".equals(verdict.reason) && fake.getInventory().countItem(Items.RAW_IRON) == 19,
            verdict.message().getString());
        fake.getInventory().setItem(1, new ItemStack(Items.RAW_IRON, 25));
        expect(source, "HUB: with 20 Raw Iron the Build Gun's hologram is green", BuildGun.hubCheck(fake, at, company).ok(), BuildGun.hubCheck(fake, at, company).message().getString());
        BuildGun.act(fake, company);
        HubBlockEntity hub = level.getBlockEntity(spot.terminal()) instanceof HubBlockEntity h ? h : null;
        int guns = 0;
        for (int i = 0; i < fake.getInventory().getContainerSize(); i++) if (fake.getInventory().getItem(i).getItem() instanceof BuildGunItem) guns++;
        expect(source, "HUB: built with the Build Gun for 20 Raw Iron, and it hands out no Build Gun", hub != null && hub.prefab && fake.getInventory().countItem(Items.RAW_IRON) == 5 && guns == 1,
            "HUB " + (hub != null) + ", raw iron left " + fake.getInventory().countItem(Items.RAW_IRON) + ", guns " + guns);
        if (hub == null) return;
        // the gun finds the HUB from any of its blocks, to take it down: its terminal, its Craft Station, a block of its shell
        BlockPos wall = null;
        for (long key : Templates.hub().keys()) {
            BlockState part = Templates.hub().at(key, hub.builtLevel);
            if (part == null || part.isAir() || !Prefabs.isPart(part) || part.getBlock() instanceof HubBlock) continue;
            BlockPos local = BlockPos.of(key);
            wall = spot.at(local.getX(), local.getY(), local.getZ());
            break;
        }
        expect(source, "HUB: the Build Gun's dismantle mode finds it from its terminal, its Craft Station and its shell",
            Prefabs.hubAt(level, spot.terminal()) == hub && Prefabs.hubAt(level, spot.at(Templates.PRESS_BASIN[0], Templates.PRESS_BASIN[1], Templates.PRESS_BASIN[2])) == hub
                && wall != null && Prefabs.hubAt(level, wall) == hub, "shell block " + (wall == null ? "none" : level.getBlockState(wall)));

        // the Craft Station: protected like the HUB, run by hand with the hand cranks
        BlockPos press = spot.at(Templates.PRESS[0], Templates.PRESS[1], Templates.PRESS[2]);
        BlockPos pressBasin = spot.at(Templates.PRESS_BASIN[0], Templates.PRESS_BASIN[1], Templates.PRESS_BASIN[2]);
        BlockPos mixerBasin = spot.at(Templates.MIXER_BASIN[0], Templates.MIXER_BASIN[1], Templates.MIXER_BASIN[2]);
        BlockPos pressCrank = spot.at(Templates.PRESS_CRANK[0], Templates.PRESS_CRANK[1], Templates.PRESS_CRANK[2]);
        BlockPos mixerCrank = spot.at(Templates.MIXER_CRANK[0], Templates.MIXER_CRANK[1], Templates.MIXER_CRANK[2]);
        boolean handAllowed = PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, fake, press, level.getBlockState(press), level.getBlockEntity(press));
        expect(source, "HUB: the Craft Station cannot be broken by hand", !handAllowed && BuildingIndex.isMember(level, pressBasin), "");
        insert(level, pressBasin, new ItemStack(Items.IRON_INGOT, 3));
        insert(level, mixerBasin, new ItemStack(item("create:iron_sheet"), 6));
        insert(level, mixerBasin, new ItemStack(item("siftec:screw"), 12));
        // nothing happens without cranking: there is no motor
        later(level, 60, () -> expect(source, "HUB: the Craft Station does nothing until cranked", count(level, pressBasin, item("create:iron_sheet")) == 0
            && level.getBlockEntity(press) instanceof KineticBlockEntity k && k.getSpeed() == 0, ""));
        for (int t = 80; t < 480; t += 5) {
            later(level, t, () -> {
                for (BlockPos crank : new BlockPos[]{pressCrank, mixerCrank}) {
                    if (level.getBlockEntity(crank) instanceof HandCrankBlockEntity c) c.turn(false);
                }
            });
        }
        later(level, 500, () -> {
            long plates = count(level, pressBasin, item("create:iron_sheet"));
            long rip = count(level, mixerBasin, item("siftec:reinforced_iron_plate"));
            expect(source, "HUB: the hand-cranked Press makes Iron Plate at the normal amount", plates == 2, plates + " plates");
            expect(source, "HUB: the hand-cranked Mixer makes Reinforced Iron Plate", rip == 1, rip + " Reinforced Iron Plate");
            // the Biomass Burners burn Biomass, not coal, at Satisfactory's 180 MJ, and have a wire connector on top
            BlockPos b1 = spot.at(Templates.BURNER_1[0], Templates.BURNER_1[1], Templates.BURNER_1[2]);
            if (level.getBlockEntity(b1) instanceof HubBurnerBlockEntity burner) {
                expect(source, "HUB: its Biomass Burners take Biomass and leaves, not coal, and are wired from the top", HubBurnerBlockEntity.accepts(new ItemStack(item("siftec:biomass")))
                    && HubBurnerBlockEntity.accepts(new ItemStack(Items.OAK_LEAVES)) && !HubBurnerBlockEntity.accepts(new ItemStack(Items.COAL))
                    && BuildingPower.itemMj(PowerRole.BIOMASS, new ItemStack(item("siftec:biomass"))) == 180
                    && level.getBlockEntity(b1.above()) instanceof AbstractElectricalConnector, String.valueOf(level.getBlockState(b1.above())));
            } else {
                expect(source, "HUB: its first Biomass Burner stands", false, String.valueOf(level.getBlockState(b1)));
            }
            fake.getInventory().clearContent();
            boolean down = BuildGun.takeDownHub(fake, hub, company);
            int iron = fake.getInventory().countItem(Items.RAW_IRON), sheets = fake.getInventory().countItem(item("create:iron_sheet"));
            expect(source, "HUB: the Build Gun takes it down and gives back its 20 Raw Iron and the Craft Station's contents, and no HUB item",
                down && iron == 20 && sheets == 2 && !BuildingIndex.isMember(level, pressBasin) && level.getBlockState(press).isAir()
                    && !(level.getBlockEntity(spot.terminal()) instanceof HubBlockEntity),
                iron + " raw iron, " + sheets + " plates, terminal " + level.getBlockState(spot.terminal()));
        });
    }

    private static void insert(ServerLevel level, BlockPos pos, ItemStack stack) {
        Storage<ItemVariant> s = ItemStorage.SIDED.find(level, pos, Direction.UP);
        if (s == null) return;
        try (Transaction tx = Transaction.openOuter()) {
            s.insert(ItemVariant.of(stack), stack.getCount(), tx);
            tx.commit();
        }
    }

    // ------------------------------------------------------------------------------------------------ the client's view

    private static final int VX = 1200, VY = 181, VZ = 1000;

    /**
     * For the client screenshots: 0 puts every building of this set down in a row (the miners on nodes) and looks
     * at the miners from the front; 1 looks at the Constructor, Smelter and Storage Containers; 2 at their backs, where
     * the inputs are; 3 close up at the Constructor and the Smelter through their glass.
     */
    private static int view(CommandContext<CommandSourceStack> context, int n) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        var players = source.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) return 0;
        var player = players.get(0);
        if (n == 0) {
            BuildingTemplate.forget();
            BuildingTests.platform(level, VX - 10, VZ - 30, VX + 80, VZ + 30, VY, 16);
            String[] row = {"portable_miner", "miner_mk1", "miner_mk2", "miner_mk3", "constructor", "smelter", "storage_container", "industrial_storage_container"};
            int x = VX;
            for (String id : row) {
                BuildingDef def = Buildings.def(id);
                if (def == null) continue;
                BuildingTemplate t = BuildingTemplate.of(def);
                int half = t == null ? 1 : t.maxX;
                x += half;
                Placement p = new Placement(def, new BlockPos(x, VY, VZ), Direction.NORTH, false);
                if (def.node()) {
                    int[] c = def.nodeCell();
                    BlockPos at = p.at(c[0], c[1], c[2]);
                    node(level, id.equals("miner_mk3") ? NodeType.COPPER : NodeType.IRON, id.equals("miner_mk2") ? Purity.IMPURE : id.equals("miner_mk3") ? Purity.PURE : Purity.NORMAL,
                        at.getX(), at.getY(), at.getZ());
                }
                Buildings.Check check = Buildings.place(level, p, null, null, true);
                report(source, "view: " + id + " " + (check.ok() ? "built" : check.message().getString()) + " at " + p.anchor().toShortString());
                x += half + 3;
            }
        }
        switch (n) {
            case 0 -> player.teleportTo(level, VX + 12.5, VY + 4, VZ + 12.5, Set.of(), 180f, 14f, true);
            case 1 -> player.teleportTo(level, VX + 36.5, VY + 4, VZ + 12.5, Set.of(), 180f, 14f, true);
            case 2 -> player.teleportTo(level, VX + 36.5, VY + 5, VZ - 17.5, Set.of(), 0f, 16f, true);
            default -> player.teleportTo(level, VX + 31.5, VY + 3, VZ + 8.5, Set.of(), 180f, 10f, true);
        }
        report(source, "view " + n + " ready");
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ after a restart

    private static int restart(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        REPORT.reset();
        Placement con = at("constructor", X, Z);
        level.getChunk(con.anchor());
        ControllerBlockEntity cc = controller(level, con);
        expect(source, "restart: the Constructor kept its recipe filter and count", cc != null && cc.filter.is(item("create:iron_sheet")) && cc.made > 0,
            cc == null ? "no controller" : cc.filter + ", " + cc.made + " made");
        List<BlockPos> motors = cc == null ? List.of() : BuildingPower.motors(cc);
        expect(source, "restart: its New Age motor kept its speed", !motors.isEmpty() && level.getBlockEntity(motors.getFirst()) instanceof MotorBlockEntity m
            && m.speedBehavior != null && m.speedBehavior.getValue() == (int) BuildingLogic.MOTOR_RPM, motors.isEmpty() ? "no motor" : String.valueOf(level.getBlockEntity(motors.getFirst())));
        later(level, 60, () -> {
            ControllerBlockEntity c2 = controller(level, con);
            // idle (its belt has run dry by now) it draws next to nothing and its motor stands; working, the motor turns
            expect(source, "restart: the Constructor is back on its grid and its motor turns while it works", c2 != null && c2.powered && (c2.idle || motorTurns(level, c2)),
                c2 == null ? "no controller" : BuildingLogic.status(level, c2).getString());
        });
        Placement mk1 = BuildingNodes.snapToNode(level, at("miner_mk1", X + 41, Z + 3));
        ControllerBlockEntity mc = controller(level, mk1);
        long before = mc == null ? -1 : mc.made;
        later(level, 100, () -> {
            ControllerBlockEntity m2 = controller(level, mk1);
            expect(source, "restart: the Miner Mk.1 still knows its node and goes on mining", m2 != null && m2.node != null && m2.made > before,
                m2 == null ? "no controller" : m2.node + ", " + before + " -> " + m2.made);
            report(source, "restart result: " + REPORT.totals());
        });
        return 1;
    }
}
