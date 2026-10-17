package com.meakaandre.siftec.building;

import com.google.gson.JsonElement;
import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.node.NodeLook;
import com.meakaandre.siftec.node.NodeType;
import com.meakaandre.siftec.node.Purity;
import com.meakaandre.siftec.power.PowerTests;
import com.meakaandre.siftec.registry.ModBlocks;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.api.behaviour.BlockEntityBehaviour;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.zurrtum.create.content.kinetics.belt.item.BeltConnectorItem;
import com.zurrtum.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.zurrtum.create.content.processing.basin.BasinBlockEntity;
import com.zurrtum.create.content.processing.burner.BlazeBurnerBlock;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Selftests for work package 9's buildings, run by the smoke test on a test world: {@code /siftec selftest buildings_b}
 * puts each one down (paid from an inventory, as the Build Gun does) on its own Alien Power Augmenter, feeds it by Create
 * belts at its item ports and by pipe at its fluid ports, and checks after a while that it made its recipe at
 * Satisfactory's rate: the Assembler (Reinforced Iron Plate), Foundry (Steel Ingot), Refinery (Plastic), Blender (Battery),
 * Manufacturer (Computer), Quantum Encoder (Superposition Oscillator), Packager (Packaged Water), Water Extractor, Oil
 * Extractor, Resource Well Pressurizer and Extractors, Particle Accelerator (Dark Matter Crystal) and Converter (Time
 * Crystal); plus the Brass Basin's four stacks and the Pure Aluminum Ingot alternate's building.
 * {@code ... restart} checks the buildings kept their settings and still run. Every check prints "SELFTEST buildings_b";
 * failures say FAIL.
 */
public final class BuildingsBTests {
    private static final int X = 4400, Y = 230, Z = 2400, STEP = 30;
    private static final String COMPANY = "selftest_wp9";
    /** Ticks the buildings get to settle before the rates are measured, and how long they are measured over. */
    private static final int WARMUP = 600, MINUTE = 1200;
    private static final long MB = FluidConstants.BUCKET / 1000;

    private record Later(long at, Runnable run) {
    }

    private static final List<Later> LATER = new ArrayList<>();
    private static final List<Runnable> EVERY_TICK = new ArrayList<>();
    private static int passed, failed;

    private BuildingsBTests() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (Runnable r : new ArrayList<>(EVERY_TICK)) r.run();
            long now = server.overworld().getGameTime();
            for (Later l : new ArrayList<>(LATER)) {
                if (l.at > now) continue;
                LATER.remove(l);
                l.run.run();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LATER.clear();
            EVERY_TICK.clear();
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("buildings_b").executes(BuildingsBTests::run)
                    .then(Commands.literal("restart").executes(BuildingsBTests::restart))))
                .then(Commands.literal("worldtest").then(Commands.literal("buildings_b").then(Commands.literal("view")
                    .then(Commands.argument("n", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 5))
                        .executes(c -> view(c, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "n")))))))));
    }

    private static void later(ServerLevel level, int ticks, Runnable run) {
        LATER.add(new Later(level.getGameTime() + ticks, run));
    }

    private static void report(CommandSourceStack source, String text) {
        String line = "SELFTEST buildings_b " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        report(source, (ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
    }

    private static Fluid fluid(String id) {
        return BuiltInRegistries.FLUID.getValue(Identifier.parse(id));
    }

    /** The test company: every milestone and MAM node done, no alternates picked. */
    private static Company company(ServerLevel level) {
        CompanyData data = CompanyData.get(level.getServer());
        Company c = data.companies().get(COMPANY);
        if (c == null) {
            c = new Company();
            c.id = COMPANY;
            c.name = "Selftest Buildings B";
            data.companies().put(COMPANY, c);
        }
        var raw = Milestones.raw();
        for (String key : new String[]{"milestones", "milestones_mam"}) {
            if (raw == null || !raw.has(key)) continue;
            for (JsonElement m : raw.getAsJsonArray(key)) c.done.add(m.getAsJsonObject().get("id").getAsString());
        }
        for (int i = 1; i <= 5; i++) c.done.add("phase_" + i);
        Companies.save(level.getServer());
        return c;
    }

    /** An inventory holding exactly a building's cost (a tag cost is paid in its first item). */
    private static SimpleContainer wallet(BuildingDef def) {
        SimpleContainer c = new SimpleContainer(54);
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
        if (level.getBlockEntity(pos) instanceof BasinBlockEntity basin) {
            long n = 0;
            for (int i = 0; i < basin.itemCapability.getContainerSize(); i++) if (basin.itemCapability.getItem(i).is(item)) n += basin.itemCapability.getItem(i).getCount();
            return n;
        }
        Storage<ItemVariant> s = ItemStorage.SIDED.find(level, pos, null);
        long n = 0;
        if (s != null) for (StorageView<ItemVariant> v : s) if (!v.isResourceBlank() && v.getResource().isOf(item)) n += v.getAmount();
        return n;
    }

    // ------------------------------------------------------------------------------------------------ belts and pipes

    /** A Create belt from {@code from} to {@code to} on the ground, owned by a company whose belts run at least this fast (RPM). */
    private static void belt(ServerLevel level, BlockPos from, BlockPos to, int rpm) {
        int dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        Direction along = dx != 0 ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        Direction.Axis shaft = along.getClockWise().getAxis();
        BlockState shaftState = block("create:shaft").defaultBlockState().setValue(BlockStateProperties.AXIS, shaft);
        level.setBlock(from, shaftState, Block.UPDATE_ALL);
        level.setBlock(to, shaftState, Block.UPDATE_ALL);
        BeltConnectorItem.createBelts(level, from, to);
        // belts move by themselves at their company's speed (no shaft drives one): owned by a company fast enough
        String company = com.meakaandre.siftec.command.LogisticsTests.beltCompany(level, (int) Math.ceil(rpm * com.meakaandre.siftec.tweak.SelfMovingBelts.ITEMS_PER_MINUTE_PER_RPM));
        int length = Math.abs(dx) + Math.abs(dz);
        for (int i = 0; i <= length; i++) {
            net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(from.relative(along, i));
            if (be != null) be.setAttached(com.meakaandre.siftec.owner.Ownership.OWNER, company);
            if (be instanceof BeltBlockEntity b) com.meakaandre.siftec.tweak.SelfMovingBelts.recheck(b);
        }
    }

    private static boolean feed(ServerLevel level, BlockPos beltStart, Direction along, ItemStack one) {
        DirectBeltInputBehaviour in = BlockEntityBehaviour.get(level, beltStart, DirectBeltInputBehaviour.TYPE);
        if (in == null || !in.canInsertFromSide(along)) return false;
        return in.handleInsertion(one.copy(), along, false).isEmpty();
    }

    /** A belt from five blocks behind an input port up to it, fed one item every few ticks, up to a total. */
    private static void beltInto(ServerLevel level, Placement p, int[] port, ItemStack one, int every, int total, int rpm) {
        BlockPos at = p.at(port[0], port[1], port[2]);
        Direction out = level.getBlockState(at).getValue(PortBlock.FACING);
        BlockPos start = at.relative(out, 5), end = at.relative(out, 1);
        belt(level, start, end, rpm);
        Direction along = out.getOpposite();
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
                if (feed(level, start, along, one)) fed[0]++;
            }
        });
    }

    /** A chest in front of an output port, which the port fills. */
    private static BlockPos chestAt(ServerLevel level, Placement p, int[] port) {
        BlockPos at = p.at(port[0], port[1], port[2]);
        BlockPos chest = at.relative(level.getBlockState(at).getValue(PortBlock.FACING));
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        return chest;
    }

    /** A pipe's worth of fluid into an input port every tick (mB a tick), as a pump would push it. */
    private static void pipeInto(ServerLevel level, Placement p, int[] port, Fluid fluid, long mbPerTick) {
        BlockPos at = p.at(port[0], port[1], port[2]);
        EVERY_TICK.add(() -> {
            Direction out = level.getBlockState(at).hasProperty(PortBlock.FACING) ? level.getBlockState(at).getValue(PortBlock.FACING) : Direction.UP;
            Storage<FluidVariant> s = FluidStorage.SIDED.find(level, at, out);
            if (s == null) return;
            try (Transaction tx = Transaction.openOuter()) {
                s.insert(FluidVariant.of(fluid), mbPerTick * MB, tx);
                tx.commit();
            }
        });
    }

    /** Drains an output port every tick, as a pump would, and counts the mB of the fluid it gives. */
    private static long[] pipeOut(ServerLevel level, Placement p, int[] port, Fluid fluid) {
        BlockPos at = p.at(port[0], port[1], port[2]);
        long[] got = {0};
        EVERY_TICK.add(() -> {
            Direction out = level.getBlockState(at).hasProperty(PortBlock.FACING) ? level.getBlockState(at).getValue(PortBlock.FACING) : Direction.UP;
            Storage<FluidVariant> s = FluidStorage.SIDED.find(level, at, out);
            if (s == null) return;
            try (Transaction tx = Transaction.openOuter()) {
                long n = s.extract(FluidVariant.of(fluid), 1000 * MB, tx);
                tx.commit();
                got[0] += n / MB;
            }
        });
        return got;
    }

    // ------------------------------------------------------------------------------------------------ placing

    private static Placement at(String id, int x, int z) {
        return new Placement(Buildings.def(id), new BlockPos(x, Y, z), Direction.NORTH, false);
    }

    private static Buildings.Check put(ServerLevel level, CommandSourceStack source, Company company, Placement p) {
        SimpleContainer w = wallet(p.def());
        Buildings.Check check = Buildings.place(level, p, company, w, false);
        expect(source, p.def().id() + " goes down and its cost is paid", check.ok() && empty(w), check.message().getString()
            + (check.blocked.isEmpty() ? "" : " first blocked " + check.blocked.getFirst().toShortString() + " " + level.getBlockState(check.blocked.getFirst())));
        return check;
    }

    /** Alien Power Augmenters behind a building (500 MW each), wired to it (through a Power Pole Mk.3 for more than one). */
    private static void power(ServerLevel level, CommandSourceStack source, Placement p, int augmenters, List<ControllerBlockEntity> powered) {
        BuildingTemplate t = BuildingTemplate.of(p.def());
        ControllerBlockEntity c = controller(level, p);
        if (t == null || c == null) return;
        List<ControllerBlockEntity> augs = new ArrayList<>();
        for (int i = 0; i < augmenters; i++) {
            BlockPos a = p.at(-6 + 12 * i, 0, t.depth + 7);
            ControllerBlockEntity aug = PowerTests.build(level, source, "alien_power_augmenter", a, Direction.NORTH);
            if (aug != null) augs.add(aug);
        }
        boolean ok;
        if (augs.size() == 1) {
            ok = PowerTests.wire(PowerTests.connector(level, augs.getFirst()), PowerTests.connector(level, c));
        } else {
            ControllerBlockEntity pole = PowerTests.build(level, source, "power_pole_mk3", p.at(0, 0, t.depth + 14), Direction.NORTH);
            var pc = PowerTests.connector(level, pole);
            ok = PowerTests.wire(pc, PowerTests.connector(level, c));
            for (ControllerBlockEntity aug : augs) ok &= PowerTests.wire(PowerTests.connector(level, aug), pc);
        }
        expect(source, p.def().id() + " is wired to " + augmenters + " Alien Power Augmenter(s)", ok && augs.size() == augmenters, augs.size() + " built");
        powered.add(c);
    }

    private static BlockPos node(ServerLevel level, NodeType type, Purity purity, BlockPos at) {
        NodeLook look = NodeLook.of(type);
        level.setBlock(at.below(), look.patchStone(), Block.UPDATE_ALL);
        if (look.fluid()) level.setBlock(at, ModBlocks.NODES.get(look.id()).get().defaultBlockState().setValue(NodeBlock.PURITY, purity), Block.UPDATE_ALL);
        else com.meakaandre.siftec.node.NodeSites.put(level, at, look, purity);
        return at;
    }

    // ------------------------------------------------------------------------------------------------ the test

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        BuildingTemplate.forget();
        BuildingTests.platform(level, X - 30, Z - 45, X + STEP * 13, Z + 45, Y, 18);
        later(level, 40, () -> runSettled(source));
        report(source, "test site ready; the test runs in two seconds");
        return 1;
    }

    /** What each building is measured on: the count when the measuring starts, and how it is read. */
    private record Measure(String what, java.util.function.LongSupplier read, long low, long high, int minutes) {
    }

    private static void runSettled(CommandSourceStack source) {
        ServerLevel level = source.getServer().overworld();
        passed = failed = 0;
        Company company = company(level);
        List<ControllerBlockEntity> powered = new ArrayList<>();
        List<Measure> measures = new ArrayList<>();

        // ---- the list: every work package 9 building, with its tab, unlock, logic and SU, and a template that reads
        String[][] want = {{"assembler", "part_assembly", "basin", "3072"}, {"foundry", "basic_steel_production", "basin", "3277"},
            {"refinery", "oil_processing", "basin", "6144"}, {"blender", "control_system_development", "basin", "15360"},
            {"manufacturer", "industrial_manufacturing", "assembly", "11264"}, {"quantum_encoder", "quantum_encoding", "assembly", "204800"},
            {"packager", "fluid_packaging", "packager", "2048"}, {"water_extractor", "coal_power", "extractor", "4096"},
            {"oil_extractor", "oil_processing", "extractor", "8192"}, {"resource_well_pressurizer", "advanced_aluminum_production", "pressurizer", "30720"},
            {"resource_well_extractor", "advanced_aluminum_production", "well_extractor", "0"},
            {"particle_accelerator", "particle_enrichment", "processor", "102400"}, {"converter", "matter_conversion", "processor", "51200"},
            {"nuclear_power_plant", "nuclear_power", "power", "0"}};
        for (String[] w : want) {
            BuildingDef def = Buildings.def(w[0]);
            BuildingTemplate t = def == null ? null : BuildingTemplate.of(def);
            expect(source, w[0] + " is listed with its unlock, logic and SU, and its template reads", def != null && t != null && def.unlock().equals(w[1])
                    && def.logic().equals(w[2]) && def.su() == Integer.parseInt(w[3]) && !def.dev() && def.category() == Category.PRODUCTION == !w[0].equals("nuclear_power_plant"),
                def == null ? "missing" : def.category() + " " + def.unlock() + " " + def.logic() + " su " + def.su() + (t == null ? " no template: " + BuildingTemplate.problem(w[0]) : ""));
        }
        // the Pure Aluminum Ingot alternate runs in the Foundry's heated Mixer
        var pure = level.getServer().getRecipeManager().byKey(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE,
            Siftec.id("mixing/foundry/pure_aluminum_ingot")));
        expect(source, "the Pure Aluminum Ingot alternate is a Foundry recipe (heated Mixer)", pure.isPresent(), "");

        int x = X;
        // ---- Assembler: Reinforced Iron Plate (6 Iron Plate + 12 Screw, 12 s: 5 a minute)
        Placement asm = at("assembler", x, Z);
        put(level, source, company, asm);
        ControllerBlockEntity ac = controller(level, asm);
        if (ac != null) BuildingLogic.setFilter(level, ac, new ItemStack(item("siftec:reinforced_iron_plate")));
        beltInto(level, asm, new int[]{-1, 0, 10}, new ItemStack(item("create:iron_sheet")), 8, 400, 64);
        beltInto(level, asm, new int[]{1, 0, 10}, new ItemStack(item("siftec:screw")), 4, 800, 64);
        BlockPos asmChest = chestAt(level, asm, new int[]{0, 0, 0});
        power(level, source, asm, 1, powered);
        measures.add(new Measure("Assembler: 5 Reinforced Iron Plate a minute (12 s each)", () -> made(ac), 4, 6, 1));

        // ---- Foundry: Steel Ingot (3 Iron Ore + 3 Coal -> 3 Steel Ingot, 4 s: 15 recipes a minute), heated by its Blaze Burner
        x += STEP;
        Placement fo = at("foundry", x, Z);
        put(level, source, company, fo);
        ControllerBlockEntity fc = controller(level, fo);
        if (fc != null) BuildingLogic.setFilter(level, fc, new ItemStack(item("cgs:steel_ingot")));
        beltInto(level, fo, new int[]{-1, 0, 8}, new ItemStack(Items.RAW_IRON), 3, 1200, 128);
        beltInto(level, fo, new int[]{1, 0, 8}, new ItemStack(Items.COAL), 3, 1200, 128);
        BlockPos foChest = chestAt(level, fo, new int[]{0, 0, 0});
        power(level, source, fo, 1, powered);
        measures.add(new Measure("Foundry: 15 Steel Ingot recipes a minute (4 s each)", () -> made(fc), 13, 16, 1));

        // ---- Refinery: Plastic (3 m3 Crude Oil -> 2 Plastic + 1 m3 Heavy Oil Residue, 6 s: 10 a minute)
        x += STEP;
        Placement re = at("refinery", x, Z);
        put(level, source, company, re);
        ControllerBlockEntity rc = controller(level, re);
        if (rc != null) BuildingLogic.setFilter(level, rc, new ItemStack(item("siftec:plastic")));
        pipeInto(level, re, new int[]{3, 0, 12}, fluid("createdieselgenerators:crude_oil"), 3);
        long[] hor = pipeOut(level, re, new int[]{1, 0, 0}, fluid("siftec:heavy_oil_residue"));
        BlockPos reChest = chestAt(level, re, new int[]{-1, 0, 0});
        power(level, source, re, 1, powered);
        measures.add(new Measure("Refinery: 10 Plastic recipes a minute (6 s each)", () -> made(rc), 9, 11, 1));

        // ---- Blender: Battery (2.5 m3 Sulfuric Acid + 2 m3 Alumina Solution + 1 Aluminum Casing -> 1 Battery + 1.5 m3
        // Water, 3 s: 20 a minute), in its Brass Basin
        x += STEP;
        Placement bl = at("blender", x, Z);
        put(level, source, company, bl);
        ControllerBlockEntity bc = controller(level, bl);
        if (bc != null) BuildingLogic.setFilter(level, bc, new ItemStack(item("siftec:battery")));
        beltInto(level, bl, new int[]{-3, 0, 10}, new ItemStack(item("siftec:aluminum_casing")), 2, 1200, 64);
        pipeInto(level, bl, new int[]{1, 0, 10}, fluid("siftec:sulfuric_acid"), 4);
        pipeInto(level, bl, new int[]{3, 0, 10}, fluid("siftec:alumina_solution"), 4);
        long[] water = pipeOut(level, bl, new int[]{1, 0, 0}, fluid("minecraft:water"));
        BlockPos blChest = chestAt(level, bl, new int[]{-1, 0, 0});
        power(level, source, bl, 1, powered);
        measures.add(new Measure("Blender: 20 Batteries a minute (3 s each)", () -> made(bc), 17, 21, 1));

        // ---- Manufacturer: Computer (4 Circuit Board + 8 Cable + 16 Plastic, 24 s: 2.5 a minute): the Core in the Brass
        // Basin, then three Deployers add the last Circuit Board, Cable and Plastic
        x += STEP;
        Placement ma = at("manufacturer", x, Z);
        put(level, source, company, ma);
        ControllerBlockEntity mc = controller(level, ma);
        if (mc != null) BuildingLogic.setFilter(level, mc, new ItemStack(item("siftec:computer")));
        beltInto(level, ma, new int[]{-3, 0, 14}, new ItemStack(item("siftec:circuit_board")), 10, 400, 64);
        beltInto(level, ma, new int[]{-1, 0, 14}, new ItemStack(item("siftec:cable")), 6, 600, 64);
        beltInto(level, ma, new int[]{1, 0, 14}, new ItemStack(item("siftec:plastic")), 3, 1200, 64);
        BlockPos maChest = chestAt(level, ma, new int[]{0, 0, 0});
        power(level, source, ma, 1, powered);
        measures.add(new Measure("Manufacturer: 5 Computers in two minutes (24 s each)", () -> made(mc), 4, 6, 2));

        // ---- Quantum Encoder: Superposition Oscillator (6 Dark Matter Crystal + 1 Crystal Oscillator + 9 Alclad Aluminum
        // Sheet + 25 m3 Excited Photonic Matter -> 1 + 25 m3 Dark Matter Residue, 12 s: 5 a minute)
        x += STEP;
        Placement qe = at("quantum_encoder", x, Z);
        put(level, source, company, qe);
        ControllerBlockEntity qc = controller(level, qe);
        if (qc != null) BuildingLogic.setFilter(level, qc, new ItemStack(item("siftec:superposition_oscillator")));
        beltInto(level, qe, new int[]{-3, 0, 18}, new ItemStack(item("siftec:dark_matter_crystal")), 6, 600, 64);
        beltInto(level, qe, new int[]{-1, 0, 18}, new ItemStack(item("siftec:crystal_oscillator")), 30, 120, 64);
        beltInto(level, qe, new int[]{1, 0, 18}, new ItemStack(item("siftec:alclad_aluminum_sheet")), 4, 900, 64);
        pipeInto(level, qe, new int[]{3, 0, 18}, fluid("siftec:excited_photonic_matter"), 6);
        long[] dmr = pipeOut(level, qe, new int[]{1, 0, 0}, fluid("siftec:dark_matter_residue"));
        BlockPos qeChest = chestAt(level, qe, new int[]{-1, 0, 0});
        power(level, source, qe, 2, powered);
        measures.add(new Measure("Quantum Encoder: 5 Superposition Oscillators a minute (12 s each)", () -> made(qc), 4, 6, 1));

        // ---- Packager: Packaged Water (1 Empty Canister + 1 m3 Water a second: 60 a minute)
        x += STEP;
        Placement pk = at("packager", x, Z);
        put(level, source, company, pk);
        ControllerBlockEntity pc = controller(level, pk);
        beltInto(level, pk, new int[]{-1, 0, 6}, new ItemStack(item("siftec:empty_canister")), 15, 200, 64);
        pipeInto(level, pk, new int[]{1, 0, 6}, fluid("minecraft:water"), 3);
        BlockPos pkChest = chestAt(level, pk, new int[]{-1, 0, 0});
        long[] unpacked = pipeOut(level, pk, new int[]{1, 0, 0}, fluid("minecraft:water"));
        power(level, source, pk, 1, powered);
        measures.add(new Measure("Packager: 60 Packaged Water a minute (1 s each)", () -> count(level, pkChest, item("siftec:packaged_water")), 50, 62, 1));

        // ---- Water Extractor in a pool: 120 m3 (4,800 mB) of water a minute
        x += STEP;
        for (int dx = -5; dx <= 5; dx++)
            for (int dz = -2; dz <= 9; dz++) {
                level.setBlock(new BlockPos(x + dx, Y - 1, Z - dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(new BlockPos(x + dx, Y, Z - dz), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            }
        Placement we = at("water_extractor", x, Z);
        put(level, source, company, we);
        ControllerBlockEntity wc = controller(level, we);
        long[] wout = pipeOut(level, we, new int[]{0, 1, 0}, fluid("minecraft:water"));
        power(level, source, we, 1, powered);
        measures.add(new Measure("Water Extractor: 4,800 mB of water a minute (120 m3)", () -> wout[0], 4500, 4900, 1));
        Buildings.Check dry = Buildings.check(level, at("water_extractor", x + 14, Z), company, wallet(Buildings.def("water_extractor")), false);
        expect(source, "a Water Extractor out of the water is refused", "siftec.build_gun.no_water".equals(dry.reason), String.valueOf(dry.reason));

        // ---- Oil Extractor on a pure oil node: 240 m3 (9,600 mB) of crude oil a minute
        x += STEP;
        Placement oe = at("oil_extractor", x, Z);
        node(level, NodeType.OIL, Purity.PURE, oe.at(0, 0, 2));
        Placement oeSnapped = Buildings.snapToNode(level, at("oil_extractor", x + 1, Z + 1));
        expect(source, "the Oil Extractor snaps onto the oil node it is aimed near", oeSnapped.at(0, 0, 2).equals(oe.at(0, 0, 2)), oeSnapped.anchor().toShortString());
        put(level, source, company, oeSnapped);
        long[] oout = pipeOut(level, oeSnapped, new int[]{0, 0, 0}, fluid("createdieselgenerators:crude_oil"));
        power(level, source, oeSnapped, 1, powered);
        measures.add(new Measure("Oil Extractor on a pure node: 9,600 mB of crude oil a minute (240 m3)", () -> oout[0], 9000, 9700, 1));
        Buildings.Check offOil = Buildings.check(level, at("oil_extractor", x + 14, Z), company, wallet(Buildings.def("oil_extractor")), false);
        expect(source, "an Oil Extractor off any oil node is refused", "siftec.build_gun.no_node".equals(offOil.reason), String.valueOf(offOil.reason));

        // ---- Resource Well: a Pressurizer on a normal nitrogen well, two Extractors near it (60 m3 a minute each), one too far
        x += STEP;
        Placement pr = at("resource_well_pressurizer", x, Z);
        node(level, NodeType.NITROGEN, Purity.NORMAL, pr.at(0, 0, 2));
        put(level, source, company, pr);
        power(level, source, pr, 1, powered);
        Placement w1 = at("resource_well_extractor", x - 8, Z + 2), w2 = at("resource_well_extractor", x + 8, Z + 2),
            far = at("resource_well_extractor", x + 12, Z + 36);
        put(level, source, company, w1);
        put(level, source, company, w2);
        put(level, source, company, far);
        long[] n1 = pipeOut(level, w1, new int[]{0, 0, 0}, fluid("siftec:nitrogen")), n2 = pipeOut(level, w2, new int[]{0, 0, 0}, fluid("siftec:nitrogen")),
            n3 = pipeOut(level, far, new int[]{0, 0, 0}, fluid("siftec:nitrogen"));
        measures.add(new Measure("Resource Well Extractors on a running Pressurizer: 2,400 mB of nitrogen a minute each (60 m3)", () -> Math.min(n1[0], n2[0]), 2200, 2450, 1));
        measures.add(new Measure("a Resource Well Extractor 38 blocks from the Pressurizer gets nothing", () -> n3[0], 0, 0, 1));

        // ---- Particle Accelerator: Dark Matter Crystal (1 Diamond + 5 m3 Dark Matter Residue, 2 s: 30 a minute)
        x += STEP;
        Placement pa = at("particle_accelerator", x, Z);
        put(level, source, company, pa);
        ControllerBlockEntity pac = controller(level, pa);
        if (pac != null) BuildingLogic.setFilter(level, pac, new ItemStack(item("siftec:dark_matter_crystal")));
        beltInto(level, pa, new int[]{-2, 0, 20}, new ItemStack(Items.DIAMOND), 20, 120, 64);
        pipeInto(level, pa, new int[]{2, 0, 20}, fluid("siftec:dark_matter_residue"), 8);
        BlockPos paChest = chestAt(level, pa, new int[]{0, 0, 0});
        // Dark Matter Crystal draws up to 1,500 MW at the top of its cycle
        power(level, source, pa, 4, powered);
        measures.add(new Measure("Particle Accelerator: 30 Dark Matter Crystals a minute (2 s each)", () -> made(pac), 27, 31, 1));

        // ---- Converter: Time Crystal (2 Diamond, 10 s: 6 a minute), its soul fire and fan turning
        x += STEP;
        Placement co = at("converter", x, Z);
        put(level, source, company, co);
        ControllerBlockEntity cc = controller(level, co);
        if (cc != null) BuildingLogic.setFilter(level, cc, new ItemStack(item("siftec:time_crystal")));
        beltInto(level, co, new int[]{-1, 0, 8}, new ItemStack(Items.DIAMOND), 30, 100, 64);
        BlockPos coChest = chestAt(level, co, new int[]{-1, 0, 0});
        power(level, source, co, 1, powered);
        measures.add(new Measure("Converter: 6 Time Crystals a minute (10 s each)", () -> made(cc), 5, 7, 1));

        // ---- the Brass Basin holds four stacks of an item: 200 Screws go into the Manufacturer's through its port
        later(level, 20, () -> {
            BlockPos port = ma.at(3, 0, 14);
            Storage<ItemVariant> into = ItemStorage.SIDED.find(level, port, level.getBlockState(port).getValue(PortBlock.FACING));
            long in = 0;
            if (into != null) {
                try (Transaction tx = Transaction.openOuter()) {
                    in = into.insert(ItemVariant.of(item("siftec:screw")), 300, tx);
                    tx.commit();
                }
            }
            BlockPos brass = mc == null ? null : BuildingLogic.cell(mc, ModBlocks.BRASS_BASIN.get());
            expect(source, "the Brass Basin takes four stacks (256) of one item, a plain Basin one", in == 256 && count(level, brass, item("siftec:screw")) == 256,
                in + " went in, " + count(level, brass, item("siftec:screw")) + " in the Brass Basin");
        });

        // ---- once running: the heated buildings' Blaze Burners are lit, everything is on its grid
        later(level, 200, () -> {
            for (ControllerBlockEntity c : powered) {
                if (c == null || c.def() == null) continue;
                expect(source, c.def().id() + " has power from its grid", c.powered, BuildingLogic.status(level, c).getString());
            }
            for (ControllerBlockEntity c : new ControllerBlockEntity[]{fc, rc, bc}) {
                BlockPos burner = c == null ? null : BuildingLogic.cell(c, block("create:blaze_burner"));
                BlockState s = burner == null ? null : level.getBlockState(burner);
                expect(source, (c == null || c.def() == null ? "?" : c.def().id()) + "'s Blaze Burner is kept lit (heated) on power",
                    s != null && s.hasProperty(BlazeBurnerBlock.HEAT_LEVEL) && s.getValue(BlazeBurnerBlock.HEAT_LEVEL).ordinal() >= BlazeBurnerBlock.HeatLevel.KINDLED.ordinal(),
                    String.valueOf(s));
            }
        });

        // ---- every thirty seconds, where things are (for working out a failure)
        for (int t = 200; t <= WARMUP + 2 * MINUTE; t += 600) {
            final int at = t;
            later(level, t, () -> {
                StringBuilder b = new StringBuilder("at " + at + ":");
                for (ControllerBlockEntity c : new ControllerBlockEntity[]{ac, fc, rc, bc, mc, qc, pc, wc, pac, cc}) {
                    if (c == null || c.def() == null) continue;
                    b.append(" | ").append(BuildingLogic.status(level, c).getString());
                }
                b.append(" | manufacturer line ").append(mc == null ? "-" : mc.line).append(", quantum line ").append(qc == null ? "-" : qc.line);
                b.append(" | oil ").append(oout[0]).append(" mB, water ").append(wout[0]).append(" mB, nitrogen ").append(n1[0]).append("/").append(n2[0]).append("/").append(n3[0]);
                report(source, b.toString());
            });
        }

        // ---- measure: each over its minutes once settled
        long[] start = new long[measures.size()];
        later(level, WARMUP, () -> {
            for (int i = 0; i < measures.size(); i++) start[i] = measures.get(i).read().getAsLong();
            report(source, "measuring from " + java.util.Arrays.toString(start));
        });
        for (int i = 0; i < measures.size(); i++) {
            final int k = i;
            Measure m = measures.get(i);
            later(level, WARMUP + m.minutes() * MINUTE, () -> {
                long got = m.read().getAsLong() - start[k];
                expect(source, m.what(), got >= m.low() && got <= m.high(), got + " (wanted " + m.low() + " to " + m.high() + ")");
            });
        }
        later(level, WARMUP + 2 * MINUTE + 20, () -> {
            expect(source, "the output ports put each building's goods into a chest", count(level, asmChest, item("siftec:reinforced_iron_plate")) > 0
                    && count(level, foChest, item("cgs:steel_ingot")) > 0 && count(level, reChest, item("siftec:plastic")) > 0 && count(level, blChest, item("siftec:battery")) > 0
                    && count(level, paChest, item("siftec:dark_matter_crystal")) > 0 && count(level, coChest, item("siftec:time_crystal")) > 0,
                "assembler " + count(level, asmChest, item("siftec:reinforced_iron_plate")) + ", foundry " + count(level, foChest, item("cgs:steel_ingot"))
                    + ", refinery " + count(level, reChest, item("siftec:plastic")) + ", blender " + count(level, blChest, item("siftec:battery")) + ", accelerator "
                    + count(level, paChest, item("siftec:dark_matter_crystal")) + ", converter " + count(level, coChest, item("siftec:time_crystal")));
            expect(source, "the fluid outputs give what the recipes leave: Heavy Oil Residue, Water and Dark Matter Residue", hor[0] > 0 && water[0] > 0 && dmr[0] > 0,
                hor[0] + " mB residue, " + water[0] + " mB water, " + dmr[0] + " mB dark matter residue");
            // the Packager empties packaged water too: four go in, four canisters and 160 mB of water come out
            if (pc != null) pc.input.setItem(0, new ItemStack(item("siftec:packaged_water"), 4));
            long canistersBefore = count(level, pkChest, item("siftec:empty_canister")), waterBefore = unpacked[0];
            later(level, 200, () -> {
                long cans = count(level, pkChest, item("siftec:empty_canister")) - canistersBefore, out = unpacked[0] - waterBefore;
                expect(source, "Packager: its Item Drain empties packaged water", cans == 4 && out >= 150, cans + " canisters, " + out + " mB of water");
                // dismantling gives back the cost and what is inside
                ControllerBlockEntity dc = controller(level, asm);
                List<ItemStack> back = dc == null ? List.of() : Buildings.dismantle(level, dc);
                int rip = 0;
                for (ItemStack s : back) if (s.is(item("siftec:reinforced_iron_plate"))) rip += s.getCount();
                expect(source, "the Assembler dismantles with its cost (8 Reinforced Iron Plate, 4 Rotor, 10 Cable) and contents", rip >= 8, back.size() + " stacks back, " + rip + " plates");
                report(source, "result: " + passed + " passed, " + failed + " failed");
            });
        });
    }

    private static long made(@Nullable ControllerBlockEntity c) {
        return c == null ? -1 : c.made;
    }

    // ------------------------------------------------------------------------------------------------ after a restart

    private static int restart(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        passed = failed = 0;
        Placement ma = at("manufacturer", X + 4 * STEP, Z), pa = at("particle_accelerator", X + 10 * STEP, Z);
        level.getChunk(ma.anchor());
        level.getChunk(pa.anchor());
        ControllerBlockEntity mc = controller(level, ma), pac = controller(level, pa);
        expect(source, "restart: the Manufacturer and the Particle Accelerator kept their recipe filters", mc != null && mc.filter.is(item("siftec:computer"))
            && pac != null && pac.filter.is(item("siftec:dark_matter_crystal")), (mc == null ? "-" : mc.filter.toString()) + ", " + (pac == null ? "-" : pac.filter.toString()));
        long before = pac == null ? -1 : pac.made;
        // its belt ran dry before the restart: more diamonds through its input port, and the residue piped in again
        BlockPos diamondsIn = pa.at(-2, 0, 20);
        var port = ItemStorage.SIDED.find(level, diamondsIn, level.getBlockState(diamondsIn).hasProperty(PortBlock.FACING)
            ? level.getBlockState(diamondsIn).getValue(PortBlock.FACING) : null);
        if (port != null) {
            try (Transaction tx = Transaction.openOuter()) {
                port.insert(ItemVariant.of(Items.DIAMOND), 16, tx);
                tx.commit();
            }
        }
        pipeInto(level, pa, new int[]{2, 0, 20}, fluid("siftec:dark_matter_residue"), 8);
        later(level, 200, () -> {
            ControllerBlockEntity p2 = controller(level, pa);
            expect(source, "restart: the Particle Accelerator is back on its grid and goes on making", p2 != null && p2.powered && p2.made > before,
                p2 == null ? "no controller" : before + " -> " + p2.made + ", " + BuildingLogic.status(level, p2).getString());
            report(source, "restart result: " + passed + " passed, " + failed + " failed");
        });
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ the client's view

    private static final int VX = 1500, VY = 181, VZ = 1000;

    /**
     * For the client screenshots: 0 puts every work package 9 building down in a row (with the work package 5 and 6 ones
     * beside them, all in their new look) and looks along it; 1 to 5 look at stretches of the row up close.
     */
    private static int view(CommandContext<CommandSourceStack> context, int n) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        var players = source.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) return 0;
        var player = players.get(0);
        Map<String, Integer> where = new LinkedHashMap<>();
        String[] row = {"constructor", "smelter", "assembler", "foundry", "refinery", "blender", "manufacturer", "quantum_encoder", "packager",
            "water_extractor", "oil_extractor", "resource_well_pressurizer", "resource_well_extractor", "particle_accelerator", "converter"};
        int x = VX;
        for (String id : row) {
            BuildingDef def = Buildings.def(id);
            BuildingTemplate t = def == null ? null : BuildingTemplate.of(def);
            if (t == null) continue;
            x += t.maxX;
            where.put(id, x);
            x += t.maxX + 3;
        }
        if (n == 0) {
            BuildingTemplate.forget();
            BuildingTests.platform(level, VX - 10, VZ - 30, x + 10, VZ + 30, VY, 24);
            for (String id : row) {
                Integer at = where.get(id);
                BuildingDef def = Buildings.def(id);
                if (at == null || def == null) continue;
                Placement p = new Placement(def, new BlockPos(at, VY, VZ), Direction.NORTH, false);
                if (def.intake() != null) {
                    for (int dx = -4; dx <= 4; dx++)
                        for (int dz = -1; dz <= 7; dz++) level.setBlock(p.at(dx, 0, dz), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
                }
                if (def.node()) {
                    int[] c = def.nodeCell();
                    node(level, def.fluidNode().equals("oil") ? NodeType.OIL : NodeType.NITROGEN, Purity.NORMAL, p.at(c[0], c[1], c[2]));
                }
                Buildings.Check check = Buildings.place(level, p, null, null, true);
                report(source, "view: " + id + " " + (check.ok() ? "built" : check.message().getString()) + " at " + p.anchor().toShortString());
            }
        }
        int mid = (VX + x) / 2;
        // the camera hangs in the air (it would fall into the sea off the platform)
        player.getAbilities().mayfly = true;
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        switch (n) {
            case 0 -> player.teleportTo(level, mid + 0.5, VY + 30, VZ + 60.5, java.util.Set.of(), 180f, 25f, true);
            case 1 -> player.teleportTo(level, where.getOrDefault("assembler", VX) + 4.5, VY + 8, VZ + 22.5, java.util.Set.of(), 160f, 15f, true);
            case 2 -> player.teleportTo(level, where.getOrDefault("blender", VX) + 6.5, VY + 9, VZ + 24.5, java.util.Set.of(), 160f, 15f, true);
            case 3 -> player.teleportTo(level, where.getOrDefault("quantum_encoder", VX) + 2.5, VY + 10, VZ + 26.5, java.util.Set.of(), 165f, 15f, true);
            case 4 -> player.teleportTo(level, where.getOrDefault("oil_extractor", VX) + 2.5, VY + 9, VZ + 22.5, java.util.Set.of(), 165f, 12f, true);
            default -> player.teleportTo(level, where.getOrDefault("particle_accelerator", VX) + 6.5, VY + 12, VZ + 30.5, java.util.Set.of(), 165f, 18f, true);
        }
        report(source, "view " + n + " ready");
        return 1;
    }
}
