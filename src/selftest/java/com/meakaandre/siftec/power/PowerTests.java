package com.meakaandre.siftec.power;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.BuildingLogic;
import com.meakaandre.siftec.building.BuildingPower;
import com.meakaandre.siftec.building.BuildingProtection;
import com.meakaandre.siftec.building.BuildingTemplate;
import com.meakaandre.siftec.building.BuildingTests;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.Placement;
import com.meakaandre.siftec.building.PowerRole;
import com.meakaandre.siftec.node.NodeMap;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.antarcticgardens.cna.content.electricity.connector.AbstractElectricalConnector;
import org.antarcticgardens.cna.content.electricity.network.ElectricalNetwork;
import org.antarcticgardens.cna.content.electricity.wire.WireType;
import org.antarcticgardens.cna.content.motor.MotorBlockEntity;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Selftests for the power grid. Every line of output starts with "SELFTEST power".
 * <ul>
 * <li>{@code /siftec selftest power}: a Coal Generator on a Power Pole Mk.1 runs a Constructor, then two New Age motors
 * a player wired up; the second is more than the generator makes, so the fuse trips, stays tripped after a reset while
 * the grid is still too big, and runs again once it is not. Everything is read back in SU. A second grid checks the
 * Biomass Burner, Power Storage, Alien Power Augmenter and Nuclear Power Plant; and the wire rules.</li>
 * <li>{@code /siftec selftest power restart}: after a restart the grids come back as they were.</li>
 * <li>{@code /siftec worldtest powerchain setup|restart}: a Power Tower line 800 blocks long whose middle is unloaded
 * still runs the Constructors at its far ends, without loading the middle; after a restart too, and breaking the
 * middle tower cuts them off.</li>
 * </ul>
 */
public final class PowerTests {
    private static final List<Monitor> MONITORS = new ArrayList<>();
    private static final Map<Long, Integer> LOADS = new ConcurrentHashMap<>();
    private static int allLoads;

    private interface Monitor {
        /** True when finished. */
        boolean tick();
    }

    private PowerTests() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(Commands.literal("siftec")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(Commands.literal("selftest").then(Commands.literal("power").executes(c -> grid(c, false))
                .then(Commands.literal("restart").executes(c -> grid(c, true)))))
            .then(Commands.literal("worldtest").then(Commands.literal("powerchain")
                .then(Commands.literal("setup").executes(c -> chain(c, false))).then(Commands.literal("restart").executes(c -> chain(c, true))))
                .then(Commands.literal("power").then(Commands.literal("view").then(Commands.argument("n", IntegerArgumentType.integer(0, 2))
                    .executes(c -> view(c, IntegerArgumentType.getInteger(c, "n")))))))));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (var it = MONITORS.iterator(); it.hasNext(); ) {
                Monitor m = it.next();
                try {
                    if (m.tick()) it.remove();
                } catch (RuntimeException e) {
                    Siftec.LOGGER.error("SELFTEST power monitor failed", e);
                    it.remove();
                }
            }
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            LOADS.computeIfPresent(chunk.getPos().pack(), (k, v) -> v + 1);
            allLoads++;
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            MONITORS.clear();
            LOADS.clear();
        });
    }

    static void report(CommandSourceStack source, String text) {
        String line = "SELFTEST power " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static BlockPos origin() {
        int[] o = NodeMap.origin();
        return o == null ? BlockPos.ZERO : new BlockPos(o[0], 0, o[1]);
    }

    /** A Build Gun building put down as in creative (no cost, no unlock); null (and said why) if it could not go. */
    public static @Nullable ControllerBlockEntity build(ServerLevel level, CommandSourceStack source, String id, BlockPos anchor, Direction facing) {
        BuildingDef def = Buildings.def(id);
        if (def == null) {
            report(source, "no building " + id);
            return null;
        }
        Placement p = new Placement(def, anchor, facing, false);
        Buildings.Check check = Buildings.place(level, p, null, null, true);
        if (!check.ok()) {
            report(source, "could not place " + id + " at " + anchor.toShortString() + ": " + check.message().getString()
                + (check.blocked.isEmpty() ? "" : " (first blocked " + check.blocked.getFirst().toShortString() + " " + level.getBlockState(check.blocked.getFirst()) + ")"));
            return null;
        }
        BuildingTemplate t = BuildingTemplate.of(def);
        return t != null && level.getBlockEntity(p.at(t.controller)) instanceof ControllerBlockEntity c ? c : null;
    }

    public static @Nullable AbstractElectricalConnector connector(ServerLevel level, @Nullable ControllerBlockEntity c) {
        BlockPos pos = c == null ? null : BuildingProtection.connectorOf(level, c);
        return pos != null && level.getBlockEntity(pos) instanceof AbstractElectricalConnector e ? e : null;
    }

    /** Strings a copper wire between two connectors, as New Age's wire item does (no length or count rule: the test's own). */
    public static boolean wire(@Nullable AbstractElectricalConnector a, @Nullable AbstractElectricalConnector b) {
        if (a == null || b == null || a == b) return false;
        if (a.getNetwork() == null) a.setNetwork(new ElectricalNetwork(a));
        if (b.getNetwork() == null) b.setNetwork(new ElectricalNetwork(b));
        a.connect(b, WireType.COPPER);
        return a.isConnected(b.getBlockPos());
    }

    private static void fill(ServerLevel level, @Nullable BlockPos tank, Fluid fluid, long mb) {
        Storage<FluidVariant> s = tank == null ? null : FluidStorage.SIDED.find(level, tank, null);
        if (s == null) return;
        try (Transaction tx = Transaction.openOuter()) {
            s.insert(FluidVariant.of(fluid), mb * (FluidConstants.BUCKET / 1000), tx);
            tx.commit();
        }
    }

    private static long fluidMb(ServerLevel level, @Nullable BlockPos tank) {
        Storage<FluidVariant> s = tank == null ? null : FluidStorage.SIDED.find(level, tank, null);
        long total = 0;
        if (s != null) for (StorageView<FluidVariant> v : s) total += v.getAmount();
        return total / (FluidConstants.BUCKET / 1000);
    }

    /** The tank its fluid port fills (a generator's water intake). */
    private static @Nullable BlockPos tank(ControllerBlockEntity c) {
        return BuildingPower.tank(c);
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    /** A building's status line, as its controller says it. */
    private static String status(ServerLevel level, @Nullable ControllerBlockEntity c) {
        return c == null ? "missing" : BuildingLogic.status(level, c).getString();
    }

    /** The first New Age motor in a building: its speed and the SU it gives. */
    private static String motor(ServerLevel level, @Nullable ControllerBlockEntity c) {
        List<BlockPos> motors = c == null ? List.of() : BuildingPower.motors(c);
        if (motors.isEmpty() || !(level.getBlockEntity(motors.getFirst()) instanceof KineticBlockEntity k)) return "no motor";
        return motorOf(k);
    }

    private static String motorOf(KineticBlockEntity k) {
        float speed = k.getSpeed();
        return String.format(Locale.ROOT, "motor %.0f RPM giving %.0f SU", speed, Math.abs(speed) * k.calculateAddedStressCapacity());
    }

    /** A New Age reinforced motor a player put down, shaft down, with a wire connector on top; its speed is set. */
    private static @Nullable AbstractElectricalConnector playerMotor(ServerLevel level, BlockPos at, int rpm) {
        BlockState motor = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("create_new_age", "reinforced_motor")).defaultBlockState();
        if (motor.hasProperty(BlockStateProperties.FACING)) motor = motor.setValue(BlockStateProperties.FACING, Direction.DOWN);
        BlockState conn = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("create_new_age", "electrical_connector")).defaultBlockState();
        if (conn.hasProperty(BlockStateProperties.FACING)) conn = conn.setValue(BlockStateProperties.FACING, Direction.UP);
        level.setBlockAndUpdate(at, motor);
        level.setBlockAndUpdate(at.above(), conn);
        setSpeed(level, at, rpm);
        return level.getBlockEntity(at.above()) instanceof AbstractElectricalConnector c ? c : null;
    }

    private static void setSpeed(ServerLevel level, BlockPos motor, int rpm) {
        if (level.getBlockEntity(motor) instanceof MotorBlockEntity m && m.speedBehavior != null) m.speedBehavior.setValue(rpm);
    }

    private static String grid(@Nullable ControllerBlockEntity c) {
        return c == null || c.grid == null ? "no grid" : c.grid.describe();
    }

    private static void floor(ServerLevel level, BlockPos from, int dx, int dz) {
        for (int x = 0; x < dx; x++) {
            for (int z = 0; z < dz; z++) {
                level.setBlock(from.offset(x, -1, z), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = 0; y < 20; y++) {
                    BlockPos p = from.offset(x, y, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ the test grids

    /** Where the test grids stand: north-west of spawn, high above the ground. */
    private static BlockPos site() {
        BlockPos o = origin();
        return new BlockPos(o.getX() - 160, 220, o.getZ() - 160);
    }

    private static int grid(CommandContext<CommandSourceStack> context, boolean restart) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        BlockPos s = site();
        for (int cx = (s.getX() >> 4) - 1; cx <= ((s.getX() + 84) >> 4) + 1; cx++)
            for (int cz = (s.getZ() >> 4) - 1; cz <= ((s.getZ() + 40) >> 4) + 1; cz++) level.setChunkForced(cx, cz, true);
        // grid 1: Coal Generator, Power Pole Mk.1, Constructor, two motors a player wired up
        BlockPos genAt = s.offset(4, 0, 10), poleAt = s.offset(12, 0, 6), conAt = s.offset(20, 0, 10), motorA = s.offset(12, 0, 2), motorB = s.offset(14, 0, 2);
        // grid 2: Biomass Burner, Power Storage, Alien Power Augmenter, Nuclear Power Plant and a Constructor on a Power Pole Mk.3
        BlockPos burnAt = s.offset(4, 0, 30), storeAt = s.offset(10, 0, 30), augAt = s.offset(16, 0, 30), nukeAt = s.offset(66, 0, 26),
            pole3At = s.offset(16, 0, 22), con2At = s.offset(36, 0, 30);
        if (!restart) {
            floor(level, s, 84, 40);
            ControllerBlockEntity gen = build(level, source, "coal_generator", genAt, Direction.SOUTH);
            ControllerBlockEntity pole = build(level, source, "power_pole_mk1", poleAt, Direction.SOUTH);
            ControllerBlockEntity con = build(level, source, "constructor", conAt, Direction.SOUTH);
            ControllerBlockEntity burner = build(level, source, "biomass_burner", burnAt, Direction.SOUTH);
            ControllerBlockEntity store = build(level, source, "power_storage", storeAt, Direction.SOUTH);
            ControllerBlockEntity aug = build(level, source, "alien_power_augmenter", augAt, Direction.SOUTH);
            ControllerBlockEntity nuke = build(level, source, "nuclear_power_plant", nukeAt, Direction.SOUTH);
            ControllerBlockEntity pole3 = build(level, source, "power_pole_mk3", pole3At, Direction.SOUTH);
            ControllerBlockEntity con2 = build(level, source, "constructor", con2At, Direction.SOUTH);
            AbstractElectricalConnector ma = playerMotor(level, motorA, 0), mb = playerMotor(level, motorB, 0);
            if (gen != null) {
                fill(level, tank(gen), Fluids.WATER, 8000);
                gen.input.setItem(0, new ItemStack(Items.COAL, 64));
            }
            if (burner != null) burner.input.setItem(0, new ItemStack(item("siftec:solid_biofuel"), 64));
            if (nuke != null) {
                fill(level, tank(nuke), Fluids.WATER, 16000);
                nuke.input.setItem(0, new ItemStack(item("siftec:uranium_fuel_rod"), 4));
            }
            report(source, "setup: coal generator " + (gen != null) + ", pole " + (pole != null) + ", constructor " + (con != null) + ", player motors " + (ma != null) + " " + (mb != null)
                + "; burner " + (burner != null) + ", storage " + (store != null) + ", augmenter " + (aug != null) + ", nuclear plant " + (nuke != null)
                + ", pole Mk.3 " + (pole3 != null) + ", constructor " + (con2 != null) + "; the generator's water " + (gen == null ? -1 : fluidMb(level, tank(gen))) + " mB");
            MONITORS.add(new Monitor() {
                int ticks;

                @Override
                public boolean tick() {
                    ticks++;
                    if (ticks == 5) wireUp();
                    if (ticks == 60) {
                        report(source, "A, a Constructor alone: constructor " + status(level, con) + " / " + motor(level, con) + "; generator " + status(level, gen)
                            + "; " + grid(gen));
                        setSpeed(level, motorA, 64);
                    }
                    if (ticks == 120) {
                        report(source, "B, and a player's reinforced motor at 64 RPM (8,192 SU): generator " + status(level, gen) + "; "
                            + (level.getBlockEntity(motorA) instanceof KineticBlockEntity k ? "player motor " + motorOf(k) : "no player motor") + "; constructor " + motor(level, con));
                        setSpeed(level, motorB, 64);
                    }
                    if (ticks == 180) {
                        report(source, "C, a second motor: 17,203 SU on 15,360: tripped " + (gen != null && gen.tripped) + "; constructor " + status(level, con) + " / " + motor(level, con)
                            + "; generator " + status(level, gen) + "; " + (level.getBlockEntity(motorB) instanceof KineticBlockEntity k ? "second motor " + motorOf(k) : ""));
                        boolean reset = gen != null && BuildingPowerAccess.reset(level, gen);
                        report(source, "C, reset at the generator while the grid is still too big: reset " + reset);
                    }
                    if (ticks == 200) {
                        report(source, "D, a moment after the reset: tripped again " + (gen != null && gen.tripped) + "; " + grid(gen));
                        setSpeed(level, motorB, 0);
                        boolean reset = gen != null && BuildingPowerAccess.reset(level, gen);
                        report(source, "D, the second motor stopped and the fuse reset: " + reset);
                    }
                    if (ticks == 260) {
                        report(source, "E, running again: tripped " + (gen != null && gen.tripped) + "; constructor " + status(level, con) + " / " + motor(level, con)
                            + "; generator " + status(level, gen) + "; coal burnt " + (gen == null ? -1 : gen.made) + ", water left " + (gen == null ? -1 : fluidMb(level, tank(gen))) + " mB");
                        report(source, "F, the second grid: burner " + status(level, burner) + "; storage " + status(level, store) + "; augmenter " + status(level, aug)
                            + "; nuclear plant " + status(level, nuke) + ", its waste " + (nuke == null ? "?" : nuke.output.getItem(0)) + ", rods left " + (nuke == null ? "?" : nuke.input.getItem(0))
                            + ", water left " + (nuke == null ? -1 : fluidMb(level, tank(nuke))) + " mB; constructor " + motor(level, con2) + "; " + grid(burner));
                        if (aug != null) aug.input.setItem(0, new ItemStack(item("siftec:alien_power_matrix"), 1));
                        // only the Constructor runs on from here, so the coal lasts until the restart test
                        setSpeed(level, motorA, 0);
                    }
                    if (ticks == 300) {
                        report(source, "G, the augmenter fed an Alien Power Matrix: " + status(level, aug) + "; " + grid(aug));
                        reactorTag(level, source);
                        report(source, "wires: " + WireMap.get(level).describe(level));
                        return true;
                    }
                    return false;
                }

                void wireUp() {
                    AbstractElectricalConnector p = connector(level, pole), g = connector(level, gen), c = connector(level, con);
                    boolean w1 = wire(g, p), w2 = wire(p, c), w3 = wire(p, ma), w4 = wire(p, mb);
                    // the rules a player meets with a wire in hand
                    String fifth = p == null || g == null ? "?" : String.valueOf(Wires.refuse(level, p, connector(level, burner)));
                    String second = c == null || g == null ? "?" : String.valueOf(Wires.refuse(level, c, connector(level, con2)));
                    report(source, "wired grid 1: generator-pole " + w1 + ", pole-constructor " + w2 + ", pole-motors " + w3 + " " + w4 + "; a fifth wire on the Mk.1 pole: " + fifth
                        + "; a second wire on the Constructor: " + second + "; reach between two building connectors " + (p == null || c == null ? -1 : Wires.maxLength(level, p.getBlockPos(), c.getBlockPos()))
                        + ", between two plain New Age connectors " + Wires.newAgeLength() + ", tower to tower " + Wires.TOWER_REACH);
                    AbstractElectricalConnector p3 = connector(level, pole3);
                    boolean v1 = wire(connector(level, burner), p3), v2 = wire(connector(level, store), p3), v3 = wire(connector(level, aug), p3),
                        v4 = wire(connector(level, nuke), p3), v5 = wire(connector(level, con2), p3);
                    report(source, "wired grid 2: " + v1 + " " + v2 + " " + v3 + " " + v4 + " " + v5);
                }
            });
        } else {
            ControllerBlockEntity gen = controllerAt(level, genAt, "coal_generator"), con = controllerAt(level, conAt, "constructor"),
                store = controllerAt(level, storeAt, "power_storage"), nuke = controllerAt(level, nukeAt, "nuclear_power_plant");
            report(source, "after restart: wires " + WireMap.get(level).describe(level) + "; generator tripped " + (gen != null && gen.tripped) + ", coal left "
                + (gen == null ? "?" : gen.input.getItem(0)) + "; storage banked " + (store == null ? -1 : Math.round(store.energy)) + " SU-ticks; nuclear plant waste " + (nuke == null ? "?" : nuke.output.getItem(0)));
            MONITORS.add(new Monitor() {
                int ticks;

                @Override
                public boolean tick() {
                    if (++ticks < 80) return false;
                    report(source, "after restart, 4 s on: constructor " + status(level, con) + " / " + motor(level, con) + "; generator " + status(level, gen)
                        + "; storage " + status(level, store));
                    return true;
                }
            });
        }
        return 1;
    }

    private static @Nullable ControllerBlockEntity controllerAt(ServerLevel level, BlockPos anchor, String id) {
        BuildingDef def = Buildings.def(id);
        BuildingTemplate t = def == null ? null : BuildingTemplate.of(def);
        if (t == null) return null;
        Placement p = new Placement(def, anchor, Direction.SOUTH, false);
        return level.getBlockEntity(p.at(t.controller)) instanceof ControllerBlockEntity c ? c : null;
    }

    /** New Age's reactor burns the pack's fuel rods: what its fuel tag holds now. */
    private static void reactorTag(ServerLevel level, CommandSourceStack source) {
        TagKey<Item> fuel = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("create_new_age", "nuclear/fuel"));
        report(source, "New Age reactor fuel: uranium rod " + new ItemStack(item("siftec:uranium_fuel_rod")).is(fuel) + ", plutonium rod "
            + new ItemStack(item("siftec:plutonium_fuel_rod")).is(fuel) + ", New Age's own fuel " + new ItemStack(item("create_new_age:nuclear_fuel")).is(fuel));
    }

    /** Resets a building's fuse the way sneak-using it does. */
    private static final class BuildingPowerAccess {
        static boolean reset(ServerLevel level, ControllerBlockEntity c) {
            PowerMember m = BuildingPower.member(level, c);
            return m != null && PowerGrids.reset(level, m);
        }
    }

    // ------------------------------------------------------------------------------------------------ for the screenshots

    private static final int VX = 1300, VY = 181, VZ = 1000;

    /**
     * For the client screenshots: 0 puts every power building down in a row, all wired to Power Poles and a Power Tower,
     * the Biomass Burner and Coal Generator fed so they run (fires lit, turbines turning), and looks at them from the
     * front; 1 from the side, along the wires; 2 close up at the Coal Generator and the Biomass Burner.
     */
    private static int view(CommandContext<CommandSourceStack> context, int n) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        var players = source.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) return 0;
        var player = players.get(0);
        if (n == 0) {
            BuildingTests.platform(level, VX - 12, VZ - 20, VX + 110, VZ + 30, VY, 18);
            String[] row = {"power_pole_mk1", "biomass_burner", "coal_generator", "fuel_generator", "geothermal_generator", "nuclear_power_plant",
                "alien_power_augmenter", "power_storage", "power_pole_mk2", "power_pole_mk3", "power_tower", "constructor"};
            List<ControllerBlockEntity> built = new ArrayList<>();
            int x = VX;
            for (String id : row) {
                BuildingDef def = Buildings.def(id);
                BuildingTemplate t = def == null ? null : BuildingTemplate.of(def);
                if (t == null) continue;
                x += t.maxX;
                if (def.vent() != null) {
                    // a sulfur geyser under the Geothermal Generator: magma, the geyser, one block of water, then its open vent cell
                    BlockPos v = new Placement(def, new BlockPos(x, VY, VZ), Direction.NORTH, false).at(def.vent()[0], def.vent()[1], def.vent()[2]);
                    level.setBlockAndUpdate(v.below(3), Blocks.MAGMA_BLOCK.defaultBlockState());
                    level.setBlockAndUpdate(v.below(1), Blocks.WATER.defaultBlockState());
                    level.setBlockAndUpdate(v.below(2), Block.updateFromNeighbourShapes(Blocks.POTENT_SULFUR.defaultBlockState(), level, v.below(2)));
                }
                ControllerBlockEntity c = build(level, source, id, new BlockPos(x, VY, VZ), Direction.NORTH);
                if (c != null) built.add(c);
                x += t.maxX + 3;
            }
            // the generators that can run here run; everything is wired along the row through the poles and the tower
            ControllerBlockEntity pole = null;
            for (ControllerBlockEntity c : built) {
                BuildingDef def = c.def();
                if (def == null) continue;
                if (def.power() == PowerRole.BIOMASS) c.input.setItem(0, new ItemStack(item("siftec:solid_biofuel"), 64));
                if (def.power() == PowerRole.COAL) {
                    fill(level, tank(c), Fluids.WATER, 8000);
                    c.input.setItem(0, new ItemStack(Items.COAL, 64));
                }
                if (def.power() == PowerRole.POLE) {
                    if (pole != null) wire(connector(level, pole), connector(level, c));
                    pole = c;
                }
            }
            ControllerBlockEntity first = built.isEmpty() ? null : built.getFirst();
            for (ControllerBlockEntity c : built) {
                BuildingDef def = c.def();
                if (def == null || def.power() == PowerRole.POLE || first == null) continue;
                wire(connector(level, c), connector(level, def.power() == PowerRole.NONE ? pole : first));
            }
            report(source, "view: " + built.size() + " power buildings built and wired");
        }
        switch (n) {
            case 0 -> player.teleportTo(level, VX + 40.5, VY + 9, VZ + 26.5, Set.of(), 180f, 14f, true);
            case 1 -> player.teleportTo(level, VX - 8.5, VY + 12, VZ + 4.5, Set.of(), 255f, 18f, true);
            default -> player.teleportTo(level, VX + 9.5, VY + 3, VZ + 9.5, Set.of(), 180f, 10f, true);
        }
        report(source, "view " + n + " ready");
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ a long line

    /** Six Power Tower spots: a line of five 200 blocks apart and a branch from the middle one. */
    private static BlockPos[] chain() {
        BlockPos o = origin();
        BlockPos t0 = new BlockPos(o.getX() + 64, 200, o.getZ() + 400);
        return new BlockPos[]{t0, t0.east(200), t0.east(400), t0.east(600), t0.east(800), t0.east(400).south(200)};
    }

    /**
     * A Biomass Burner at the first tower, a Constructor at the last and at the branch's end. The first and the two
     * ends are kept loaded, the three in between let go. The far Constructors run on the burner with the middle
     * unloaded and never loaded for it; the burner running dry trips the line; refuelled and reset, it runs again. After a
     * restart: it runs again with the middle unloaded, and breaking the middle tower (its chunk loaded for that) leaves
     * both ends without power.
     */
    private static int chain(CommandContext<CommandSourceStack> context, boolean restart) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        BlockPos[] t = chain();
        BlockPos burnAt = t[0].west(6), farAt = t[4].east(6), branchAt = t[5].east(6);
        int[] middle = {1, 2, 3}, ends = {0, 4, 5};
        for (int i : middle) LOADS.put(ChunkPos.pack(t[i]), 0);
        if (!restart) {
            for (BlockPos p : t) level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, true);
            for (BlockPos p : new BlockPos[]{burnAt, farAt, branchAt}) level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, true);
            for (BlockPos p : t) level.getChunk(p.getX() >> 4, p.getZ() >> 4);
            ControllerBlockEntity[] towers = new ControllerBlockEntity[t.length];
            for (int i = 0; i < t.length; i++) towers[i] = build(level, source, "power_tower", t[i], Direction.SOUTH);
            ControllerBlockEntity burner = build(level, source, "biomass_burner", burnAt, Direction.SOUTH);
            ControllerBlockEntity far = build(level, source, "constructor", farAt, Direction.SOUTH);
            ControllerBlockEntity branch = build(level, source, "constructor", branchAt, Direction.SOUTH);
            if (burner != null) burner.input.setItem(0, new ItemStack(item("siftec:solid_biofuel"), 64));
            int[][] links = {{0, 1}, {1, 2}, {2, 3}, {3, 4}, {2, 5}};
            StringBuilder wired = new StringBuilder();
            for (int[] l : links) wired.append(wire(connector(level, towers[l[0]]), connector(level, towers[l[1]])) ? "+" : "-");
            wired.append(wire(connector(level, burner), connector(level, towers[0])) ? "+" : "-");
            wired.append(wire(connector(level, far), connector(level, towers[4])) ? "+" : "-");
            wired.append(wire(connector(level, branch), connector(level, towers[5])) ? "+" : "-");
            report(source, "chain setup: towers " + Arrays.stream(t).map(BlockPos::toShortString).toList() + "; wires " + wired);
        } else {
            for (int i : ends) level.setChunkForced(t[i].getX() >> 4, t[i].getZ() >> 4, true);
            for (BlockPos p : new BlockPos[]{burnAt, farAt, branchAt}) level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, true);
            report(source, "chain after restart: " + WireMap.get(level).describe(level) + "; middle " + middleLoaded(level, t));
        }
        MONITORS.add(new Monitor() {
            int ticks, step, waited, allBefore;
            boolean settled, reported;

            ControllerBlockEntity at(BlockPos anchor, String id) {
                return controllerAt(level, anchor, id);
            }

            String ends() {
                ControllerBlockEntity far = at(farAt, "constructor"), branch = at(branchAt, "constructor"), burner = at(burnAt, "biomass_burner");
                return "far constructor powered " + (far != null && far.powered) + " (" + motor(level, far) + "), branch constructor powered " + (branch != null && branch.powered)
                    + " (" + motor(level, branch) + "); burner " + status(level, burner);
            }

            @Override
            public boolean tick() {
                ticks++;
                if (!settled) {
                    // the chunks are new: count from when the ends really tick (up to a minute)
                    if (ticks == 1 && !(level.shouldTickBlocksAt(burnAt) && level.shouldTickBlocksAt(farAt) && level.shouldTickBlocksAt(branchAt)) && ++waited < 1200) {
                        ticks = 0;
                        return false;
                    }
                    // and until the line is one grid: its new chunks' connectors have to tick once to join (up to a minute)
                    ControllerBlockEntity far = at(farAt, "constructor");
                    if (!restart && !reported && (far != null && far.powered || ticks >= 1200)) {
                        reported = true;
                        report(source, "chain all loaded (after " + ticks / 20 + " s): " + ends());
                        for (int i : middle) level.setChunkForced(t[i].getX() >> 4, t[i].getZ() >> 4, false);
                    }
                    if (restart ? ticks < 100 : !reported) return false;
                    if (middleAnyLoaded(level, t) && ++waited < 2400) return false;
                    settled = true;
                    for (int i : middle) LOADS.put(ChunkPos.pack(t[i]), 0);
                    allBefore = allLoads;
                    ticks = 0;
                    report(source, "chain middle unloaded " + !middleAnyLoaded(level, t) + " (after " + waited / 20 + " s): " + middleLoaded(level, t));
                    return false;
                }
                if (ticks % 40 != 0) return false;
                step++;
                String tag = "chain" + (restart ? " after restart" : "") + " step " + step + ": ";
                ControllerBlockEntity burner = at(burnAt, "biomass_burner");
                if (!restart) {
                    switch (step) {
                        case 1 -> {
                            report(source, tag + "middle unloaded: " + ends() + "; middle " + middleLoaded(level, t));
                            // the burner runs dry: its last fuel goes, and the line trips once what it banked is spent
                            if (burner != null) {
                                burner.input.setItem(0, ItemStack.EMPTY);
                                burner.energy = 0;
                            }
                        }
                        case 2, 3, 4, 5 -> {
                            if (burner != null && burner.tripped || step == 5) {
                                report(source, tag + "burner out of fuel: tripped " + (burner != null && burner.tripped) + "; " + ends());
                                if (burner != null) {
                                    burner.input.setItem(0, new ItemStack(item("siftec:solid_biofuel"), 64));
                                    BuildingPowerAccess.reset(level, burner);
                                }
                                step = 5;
                            }
                        }
                        case 6 -> report(source, tag + "refuelled and reset: " + ends());
                        default -> {
                            report(source, tag + "loads of the middle chunks meanwhile " + LOADS + " (chunk loads anywhere, other tests included: " + (allLoads - allBefore)
                                + "); " + WireMap.get(level).describe(level));
                            return true;
                        }
                    }
                } else {
                    switch (step) {
                        case 1 -> {
                            report(source, tag + "after the restart: " + ends() + "; middle " + middleLoaded(level, t));
                            // break the middle tower, the branch point: its chunk is loaded for that, on purpose, and let go again
                            level.setChunkForced(t[2].getX() >> 4, t[2].getZ() >> 4, true);
                            level.getChunk(t[2].getX() >> 4, t[2].getZ() >> 4);
                            ControllerBlockEntity mid = at(t[2], "power_tower");
                            List<ItemStack> back = mid == null ? List.of() : Buildings.dismantle(level, mid);
                            level.setChunkForced(t[2].getX() >> 4, t[2].getZ() >> 4, false);
                            report(source, tag + "middle tower taken down (" + back.size() + " stacks back)");
                        }
                        case 2 -> report(source, tag + "a moment later: " + ends() + "; " + WireMap.get(level).describe(level) + "; middle loads " + LOADS);
                        default -> {
                            for (BlockPos p : t) level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, false);
                            return true;
                        }
                    }
                }
                return false;
            }
        });
        return 1;
    }

    private static boolean middleAnyLoaded(ServerLevel level, BlockPos[] t) {
        for (int i = 1; i <= 3; i++) if (level.hasChunk(t[i].getX() >> 4, t[i].getZ() >> 4)) return true;
        return false;
    }

    private static String middleLoaded(ServerLevel level, BlockPos[] t) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 1; i <= 3; i++) out.append(i > 1 ? ", " : "").append("tower ").append(i).append(' ').append(level.hasChunk(t[i].getX() >> 4, t[i].getZ() >> 4) ? "loaded" : "unloaded");
        return out.append(']').toString();
    }
}
