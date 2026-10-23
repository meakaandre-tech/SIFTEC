package com.meakaandre.siftec.building;

import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.blueprint.DesignerBlockEntity;
import com.meakaandre.siftec.drone.DronePortBlockEntity;
import com.meakaandre.siftec.equip.Radar;
import com.meakaandre.siftec.mixin.building.BreakerAccessor;
import com.meakaandre.siftec.node.NodeLook;
import com.meakaandre.siftec.node.NodeSites;
import com.meakaandre.siftec.node.Purity;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.sink.Sink;
import com.zurrtum.create.api.connectivity.ConnectivityHandler;
import com.zurrtum.create.content.fluids.tank.FluidTankBlockEntity;
import com.zurrtum.create.content.kinetics.base.BlockBreakingKineticBlockEntity;
import com.zurrtum.create.content.kinetics.base.DirectionalKineticBlock;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import com.zurrtum.create.content.kinetics.belt.transport.TransportedItemStack;
import com.zurrtum.create.content.kinetics.crusher.CrushingWheelBlock;
import com.zurrtum.create.content.kinetics.fan.EncasedFanBlock;
import com.zurrtum.create.content.kinetics.fan.processing.AllFanProcessingTypes;
import com.zurrtum.create.content.logistics.depot.DepotBlockEntity;
import com.zurrtum.create.content.processing.basin.BasinBlockEntity;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.antarcticgardens.cna.content.motor.MotorBlockEntity;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * How each kind of building works, run by its controller every tick ({@link BuildingDef#logic()}):
 * <ul>
 * <li>basin (the Constructor): a Press over a Basin. The controller's filter is the Basin's, so it picks the recipe;
 * {@link BasinPace} keeps the Press to Satisfactory's time per recipe. The ports work on the Basin.</li>
 * <li>smelter: an Encased Fan blowing through lava onto a Depot. The controller takes ore in at its input port, puts
 * it on the Depot a few at a time with Satisfactory's time per item, and takes the ingots off into its output.</li>
 * <li>miner: a Drill pointing down at the node. The Drill really mines it: it breaks the node block once it has
 * regrown, at its Mk's speed and with its built-in Fortune, and what the block drops goes into the controller's output,
 * which the output port puts on a belt. The node regrows at its purity's rate.</li>
 * <li>portable_miner: the same with no power and no Fortune, slower, the ore dropping on the ground in front (it stops
 * while a full stack lies there).</li>
 * <li>storage: Item Vaults; nothing to run.</li>
 * <li>power: the generators, Power Storage, poles and towers ({@link BuildingPower}).</li>
 * </ul>
 * A powered building's New Age motors run while its grid has power ({@link BuildingPower}); "stopped" in its status
 * means they do not.
 */
public final class BuildingLogic {
    /** The speed a building's motor turns its machines at. */
    public static final float MOTOR_RPM = 64;
    /** A fan blowing up a column (the Particle Accelerator's): Create's most, so the air carries its Core into the wheels. */
    public static final float LIFT_RPM = 256;
    /** Items the Smelter puts on its Depot at once (the Depot holds one stack; a few at a time keeps the gaps short). */
    static final int SMELTER_BATCH = 4;

    private BuildingLogic() {
    }

    // ------------------------------------------------------------------------------------------------ the cells

    /** Where the building's blocks of one kind stand in the world. */
    public static List<BlockPos> cells(@Nullable ControllerBlockEntity c, Block block) {
        List<BlockPos> out = new ArrayList<>();
        Placement p = c == null ? null : c.placement();
        BuildingTemplate t = p == null ? null : BuildingTemplate.of(p.def());
        if (t == null) return out;
        for (BuildingTemplate.Cell cell : t.members) if (cell.state().getBlock() == block) out.add(p.at(cell));
        return out;
    }

    public static @Nullable BlockPos cell(@Nullable ControllerBlockEntity c, Block block) {
        List<BlockPos> all = cells(c, block);
        return all.isEmpty() ? null : all.getFirst();
    }

    private static Block create(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("create", id));
    }

    // ------------------------------------------------------------------------------------------------ placing

    /**
     * Called once the building has gone down: its New Age motors are set to the speed its machines turn at (a fan's
     * the way that blows away from it), and its Basin's filter is the controller's. The motors only turn while the
     * building's grid gives them power ({@link BuildingPower}).
     */
    static void placed(ServerLevel level, ControllerBlockEntity c) {
        BuildingDef def = c.def();
        if (def == null) return;
        Placement p = c.placement();
        BuildingTemplate t = p == null ? null : BuildingTemplate.of(def);
        for (BlockPos pos : BuildingPower.motors(c)) {
            if (!(level.getBlockEntity(pos) instanceof MotorBlockEntity motor)) continue;
            int rpm = (int) MOTOR_RPM;
            // a fan turned the wrong way would suck instead of blow: the motor turns so it blows away from it
            Direction toward = motor.getBlockState().getValue(DirectionalKineticBlock.FACING);
            BlockState next = level.getBlockState(pos.relative(toward));
            if (next.getBlock() instanceof EncasedFanBlock) {
                // a fan that lifts things up a column into Crushing Wheels (the Particle Accelerator's) runs at full
                // speed: slower, its air holds an item hovering short of the wheels
                if (next.getValue(EncasedFanBlock.FACING) == Direction.UP) rpm = (int) LIFT_RPM;
                rpm *= next.getValue(EncasedFanBlock.FACING).getAxisDirection().getStep();
            }
            // two Crushing Wheels crush only turning against each other: the right one's motor runs the other way
            if (next.getBlock() instanceof CrushingWheelBlock && t != null) {
                for (BuildingTemplate.Cell cell : t.members) if (p.at(cell).equals(pos) && cell.x() > 0) rpm = -rpm;
            }
            if (motor.speedBehavior != null) motor.speedBehavior.setValue(rpm);
            motor.setChanged();
        }
        syncFilter(level, c);
        formTanks(level, c);
        Processors.ensureCell(level, c);
        if (def.logic().equals("drone_port")) SpecialBuildings.dronePortPlaced(level, c);
        // a Blueprint Designer's volume is flyable from the moment it is built
        if (c instanceof DesignerBlockEntity designer) designer.report();
    }

    /**
     * Joins the building's Fluid Tanks into the tanks its template draws (a boiler of 2 by 2 by 2, of 3 by 3 by 8), as Create
     * does when a player puts tanks side by side, and lets each boiler count the Steam Engines on it.
     */
    static void formTanks(ServerLevel level, ControllerBlockEntity c) {
        List<BlockPos> tanks = cells(c, create("fluid_tank"));
        tanks.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        for (BlockPos pos : tanks) {
            if (level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank && tank.isController()
                && tank.getWidth() == 1 && tank.getHeight() == 1) {
                ConnectivityHandler.formMulti(tank);
            }
        }
        for (BlockPos pos : tanks) {
            if (level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank && tank.isController()) {
                tank.updateBoilerState();
                tank.updateBoilerTemperature();
            }
        }
    }

    /** The Basin's filter follows the controller's. */
    static void syncFilter(Level level, ControllerBlockEntity c) {
        BuildingDef def = c.def();
        // the Manufacturer's and the Quantum Encoder's Basin makes the Core; the line sets its filter (ProductionLogic)
        if (def == null || !def.logic().equals("basin")) return;
        List<BlockPos> basins = cells(c, create("basin"));
        basins.addAll(cells(c, ModBlocks.BRASS_BASIN.get()));
        for (BlockPos pos : basins) {
            if (level.getBlockEntity(pos) instanceof BasinBlockEntity basin && !ItemStack.matches(basin.getFilter().getFilter(), c.filter)) {
                basin.getFilter().setFilter(c.filter.copy());
            }
        }
    }

    /** Sets what the building makes (empty: anything). False if it has no recipe filter. */
    public static boolean setFilter(Level level, ControllerBlockEntity c, ItemStack stack) {
        BuildingDef def = c.def();
        if (def == null || !hasFilter(def)) return false;
        c.filter = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        c.setChanged();
        syncFilter(level, c);
        // the Converter keeps its one cell through a recipe change
        if (level instanceof ServerLevel server) Processors.ensureCell(server, c);
        return true;
    }

    /** The buildings whose controller takes a recipe filter: what their Basin, line or recipe makes. */
    public static boolean hasFilter(BuildingDef def) {
        return def.logic().equals("basin") || def.logic().equals("assembly") || def.logic().equals("processor");
    }

    // ------------------------------------------------------------------------------------------------ running

    static void tick(ServerLevel level, ControllerBlockEntity c) {
        BuildingDef def = c.def();
        if (def == null) return;
        switch (def.logic()) {
            case "miner" -> mine(level, c, def, true);
            case "portable_miner" -> mine(level, c, def, false);
            case "smelter" -> smelt(level, c);
            case "basin" -> ProductionLogic.heat(level, c);
            case "assembly" -> ProductionLogic.assemble(level, c, def);
            case "packager" -> ProductionLogic.pack(level, c);
            case "processor" -> Processors.tick(level, c, def);
            case "power" -> Generators.tick(level, c, def);
            case "extractor" -> ExtractorLogic.extract(level, c, def);
            case "pressurizer" -> ExtractorLogic.pressurize(level, c, def);
            case "well_extractor" -> ExtractorLogic.wellExtract(level, c, def);
            case "sink" -> SpecialBuildings.sink(level, c);
            case "radar" -> Radar.tick(level, c);
            case "truck_station" -> TruckStations.tick(level, c);
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ idle

    /** Ticks a building still counts as working after it last had work, so a short gap between items does not stop it. */
    static final int AWAKE_TICKS = 40;

    /**
     * True while a powered building has nothing to work on, so it draws no power, as Satisfactory's machines draw only
     * while they run ({@link BuildingPower}). It counts as working for {@link #AWAKE_TICKS} after it last had work.
     * Worked out once a tick.
     */
    static boolean idle(ServerLevel level, ControllerBlockEntity c, BuildingDef def) {
        long now = level.getGameTime();
        if (c.idleAt == now) return c.idle;
        c.idleAt = now;
        if (working(level, c, def)) c.awakeUntil = now + AWAKE_TICKS;
        c.idle = now >= c.awakeUntil;
        return c.idle;
    }

    /**
     * Whether a powered building has work right now: a miner a node and room for what it mines; the Smelter ore in or on
     * its Depot; a Constructor, Assembler, Foundry, Refinery or Blender ingredients in its Basin; the Manufacturer and the
     * Quantum Encoder ingredients in the Basin or an item on the line; the Packager something in or on its Depot or Item
     * Drain; the Particle Accelerator and the Converter a recipe they can run (or one under way); an extractor room in its
     * tank; the AWESOME Sink something to sink. The Pressurizer and the Radar Tower always run.
     */
    static boolean working(ServerLevel level, ControllerBlockEntity c, BuildingDef def) {
        return switch (def.logic()) {
            case "miner" -> nodeAt(level, c.node) != null && room(level, c);
            case "smelter" -> {
                BlockPos depot = cell(c, create("depot"));
                yield !c.input.getItem(0).isEmpty() || depot != null && level.getBlockEntity(depot) instanceof DepotBlockEntity d
                    && d.getHeldItem() != null && !d.getHeldItem().stack.isEmpty();
            }
            case "basin" -> ProductionLogic.basinHasInput(level, c, false);
            case "assembly" -> {
                boolean onLine = false;
                for (ItemStack s : c.line) onLine |= !s.isEmpty();
                yield onLine || ProductionLogic.basinHasInput(level, c, true);
            }
            case "packager" -> !c.input.getItem(0).isEmpty() || ProductionLogic.packagerHolds(level, c);
            // working only while a step of its recipe can go on, not whenever something lies inside it
            case "processor" -> Processors.progressing(level, c, def);
            case "extractor" -> !c.problem.equals("siftec.building.full");
            case "sink" -> !c.input.getItem(0).isEmpty();
            default -> true;
        };
    }

    /** What the input port may put into the controller: the Smelter takes the ores it smelts; nothing else takes anything. */
    static boolean accepts(ControllerBlockEntity c, ItemStack stack) {
        BuildingDef def = c.def();
        if (def == null) return false;
        if (def.logic().equals("smelter")) return MachineTimes.smelterTicks(stack) > 0;
        if (def.logic().equals("packager")) return ProductionLogic.packagerTakes(stack);
        if (def.logic().equals("sink")) return Sink.accepts(stack);
        return BuildingPower.acceptsFuel(def, stack);
    }

    private static boolean turning(Level level, @Nullable BlockPos machine) {
        return machine != null && level.getBlockEntity(machine) instanceof KineticBlockEntity k && k.getSpeed() != 0;
    }

    /** The node's look and purity, or null if no node stands in the node cell any more. */
    private record NodeAt(NodeLook look, Purity purity) {
    }

    private static @Nullable NodeAt nodeAt(Level level, @Nullable BlockPos pos) {
        if (pos == null || !level.isLoaded(pos)) return null;
        var site = NodeSites.at(level, pos);
        return site == null ? null : new NodeAt(site.look, site.purity);
    }

    // ------------------------------------------------------------------------------------------------ miners

    /**
     * A miner really mines its node. Its Drill breaks the node block once it has regrown (Create's own breaking, with the
     * cracks showing; the Mk sets how fast, {@link BuildingDef#breakTicks}), and what the block drops is the output: the
     * node's ore block's own loot, with the miner's built-in Fortune ({@link BuildingDef#fortune}) by vanilla's ore
     * formula. The spot is then air until it regrows at its purity's rate ({@link com.meakaandre.siftec.node.NodeRegrowth}),
     * and the miner waits, so purity caps every miner. Nothing is ever made without a block being broken. The Portable Miner has no power: its controller
     * breaks the node the same way at its own speed, the ore dropping on the ground in front.
     */
    private static void mine(ServerLevel level, ControllerBlockEntity c, BuildingDef def, boolean powered) {
        NodeAt node = nodeAt(level, c.node);
        if (node == null) {
            c.problem = "siftec.building.no_node";
            return;
        }
        BlockPos drillPos = cell(c, create("mechanical_drill"));
        // full first: a full miner is idle, and its Drill stands because it draws no power
        if (!room(level, c)) {
            c.problem = "siftec.building.full";
            return;
        }
        if (powered && !turning(level, drillPos)) {
            c.problem = "siftec.building.stopped";
            return;
        }
        c.problem = "";
        BlockState state = level.getBlockState(c.node);
        boolean regrown = node.look().isNodeBlock(state);
        if (powered) {
            // the Drill only looks for a block to break every half second after its last one: a regrown node is started at once
            if (regrown && drillPos != null && level.getBlockEntity(drillPos) instanceof BlockBreakingKineticBlockEntity drill
                && ((BreakerAccessor) drill).siftec$ticksUntilNextProgress() == -1) {
                drill.destroyNextTick();
            }
            return;
        }
        // the Portable Miner: ten crack stages over its break time, as a Drill shows them
        if (!regrown) {
            c.progress = 0;
            return;
        }
        int total = Math.max(1, def.breakTicks());
        c.progress++;
        int stage = (int) (c.progress * 10 / total);
        level.destroyBlockProgress(breakerId(c), c.node, stage >= 10 ? -1 : stage);
        if (c.progress < total) return;
        c.progress = 0;
        mined(level, c, state);
    }

    /** True if the miner can take one more break's drops (its output slot, or the ground in front of a Portable Miner). */
    static boolean room(Level level, ControllerBlockEntity c) {
        NodeAt node = nodeAt(level, c.node);
        BuildingDef def = c.def();
        if (node == null || def == null) return false;
        Item item = node.look().output();
        if (def.logic().equals("portable_miner")) return item == Items.AIR || !groundFull(level, c, item);
        ItemStack out = c.output.getItem(0);
        // a break gives at most Fortune + 1 times the block's own most (copper ore drops up to 5 raw copper, the rest one)
        int most = (def.fortune() + 1) * (item == Items.RAW_COPPER ? 5 : 1);
        return out.isEmpty() || out.is(item) && out.getCount() + most <= out.getMaxStackSize();
    }

    private static int breakerId(ControllerBlockEntity c) {
        return -1000 - (int) (c.getBlockPos().asLong() & 0x3FFFFFFF);
    }

    /** The miner a Drill belongs to, if it is the Drill of a miner and points at that miner's node. */
    public static @Nullable ControllerBlockEntity minerOf(Level level, BlockPos drill, @Nullable BlockPos breaking) {
        BlockPos controller = BuildingIndex.controllerOf(level, drill);
        if (controller == null || !(level.getBlockEntity(controller) instanceof ControllerBlockEntity c)) return null;
        BuildingDef def = c.def();
        if (def == null || !def.logic().equals("miner") || c.node == null || breaking != null && !breaking.equals(c.node)) return null;
        return c;
    }

    /** Whether a miner's Drill may break its node now: the node has regrown and the output has room. */
    public static boolean mayBreak(Level level, ControllerBlockEntity c, BlockState state) {
        NodeAt node = nodeAt(level, c.node);
        return node != null && node.look().isNodeBlock(state) && room(level, c);
    }

    /**
     * The speed a miner's Drill breaks its node at, in Create's terms (it takes 10 stages, each waiting hardness / speed
     * ticks and moving speed / hardness stages), chosen so the whole break takes the Mk's {@link BuildingDef#breakTicks}.
     */
    public static float breakSpeed(ControllerBlockEntity c, float hardness) {
        BuildingDef def = c.def();
        int ticks = def == null ? 10 : Math.max(1, def.breakTicks());
        float h = Math.max(hardness, 0.01f);
        if (ticks >= 10) {
            int wait = ticks / 10 - 1;
            return wait <= 0 ? h * 1.5f : h / (wait + 0.5f);
        }
        int step = (10 + ticks - 1) / ticks;
        return h * (step + 0.5f);
    }

    /**
     * The node block was broken by the miner: what it drops (its loot, mined with the miner's Fortune) goes to the output
     * (on the ground in front of a Portable Miner), and the ore is taken out, leaving air that regrows.
     */
    public static void mined(ServerLevel level, ControllerBlockEntity c, BlockState state) {
        BlockPos pos = c.node;
        BuildingDef def = c.def();
        if (pos == null || def == null) return;
        List<ItemStack> drops = Block.getDrops(state, level, pos, null, null, tool(level, def.fortune()));
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.destroyBlockProgress(breakerId(c), pos, -1);
        level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(state));
        int n = 0;
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            n += drop.getCount();
            if (def.logic().equals("portable_miner")) {
                BlockPos at = dropSpot(c);
                if (at == null) continue;
                ItemEntity entity = new ItemEntity(level, at.getX() + 0.5, at.getY() + 0.1, at.getZ() + 0.5, drop);
                entity.setDeltaMovement(Vec3.ZERO);
                level.addFreshEntity(entity);
            } else {
                ItemStack out = c.output.getItem(0);
                if (out.isEmpty()) {
                    c.output.setItem(0, drop.copy());
                    drop.setCount(0);
                } else if (ItemStack.isSameItemSameComponents(out, drop)) {
                    int fit = Math.min(drop.getCount(), out.getMaxStackSize() - out.getCount());
                    out.grow(fit);
                    drop.shrink(fit);
                }
                // nothing is lost: what does not fit (another drop of a node with two) goes out of the output port's front
                if (!drop.isEmpty()) {
                    BlockPos at = c.placement() == null ? c.getBlockPos() : c.placement().at(0, 0, -1);
                    Containers.dropItemStack(level, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, drop);
                }
                c.output.setChanged();
            }
        }
        c.made++;
        c.mined += n;
        c.setChanged();
    }

    /** The pickaxe a miner mines with: one that can break any node, with the miner's built-in Fortune. */
    private static ItemStack tool(ServerLevel level, int fortune) {
        ItemStack pick = new ItemStack(Items.NETHERITE_PICKAXE);
        if (fortune > 0) {
            var holder = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.FORTUNE);
            pick.enchant(holder, fortune);
        }
        return pick;
    }

    private static boolean fits(ItemStack slot, ItemStack one) {
        return slot.isEmpty() || ItemStack.isSameItemSameComponents(slot, one) && slot.getCount() < slot.getMaxStackSize();
    }

    /** Where a Portable Miner drops its ore: on the ground just in front of it. */
    public static @Nullable BlockPos dropSpot(ControllerBlockEntity c) {
        Placement p = c.placement();
        return p == null ? null : p.at(0, 0, -1);
    }

    /** True while a full stack of the ore lies where the Portable Miner drops it. */
    private static boolean groundFull(Level level, ControllerBlockEntity c, Item item) {
        BlockPos at = dropSpot(c);
        if (at == null) return true;
        int n = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(1))) if (e.getItem().is(item)) n += e.getItem().getCount();
        return n >= item.getDefaultMaxStackSize();
    }

    private static void smelt(ServerLevel level, ControllerBlockEntity c) {
        BlockPos depotPos = cell(c, create("depot"));
        if (depotPos == null || !(level.getBlockEntity(depotPos) instanceof DepotBlockEntity depot)) return;
        TransportedItemStack held = depot.getHeldItem();
        // a finished batch (what lies there no longer smelts here) goes to the output, once it all fits
        if (held != null && !held.stack.isEmpty() && MachineTimes.smelterTicks(held.stack) == 0) {
            ItemStack out = c.output.getItem(0);
            if (!out.isEmpty() && (!ItemStack.isSameItemSameComponents(out, held.stack) || out.getCount() + held.stack.getCount() > out.getMaxStackSize())) {
                c.problem = "siftec.building.full";
                return;
            }
            c.made += held.stack.getCount();
            if (out.isEmpty()) c.output.setItem(0, held.stack.copy());
            else out.grow(held.stack.getCount());
            c.output.setChanged();
            depot.depotBehaviour.removeHeldItem();
            depot.notifyUpdate();
            held = null;
        }
        boolean fan = turning(level, cell(c, create("encased_fan")));
        // an idle Smelter's fan stands (it draws no power): that is not a fault
        c.problem = fan || c.idle && c.powered ? "" : "siftec.building.stopped";
        if (held != null && !held.stack.isEmpty() || !fan) return;
        // the Depot is free: the next few ores go on, each to take Satisfactory's time under the lava's blast
        ItemStack in = c.input.getItem(0);
        int per = MachineTimes.smelterTicks(in);
        if (in.isEmpty() || per <= 0) return;
        ItemStack batch = in.split(Math.min(SMELTER_BATCH, in.getCount()));
        c.input.setChanged();
        TransportedItemStack put = new TransportedItemStack(batch);
        put.processedBy = AllFanProcessingTypes.BLASTING;
        put.processingTime = per * batch.getCount();
        depot.depotBehaviour.setCenteredHeldItem(put);
        depot.notifyUpdate();
        c.setChanged();
    }

    // ------------------------------------------------------------------------------------------------ status

    /** What the controller says when it is used: the building's name, what it makes or mines, and anything wrong. */
    public static Component status(Level level, ControllerBlockEntity c) {
        BuildingDef def = c.def();
        if (def == null) return Component.translatable("siftec.building.unknown", c.buildingId);
        List<Component> parts = new ArrayList<>();
        switch (def.logic()) {
            case "basin" -> parts.add(c.filter.isEmpty() ? Component.translatable("siftec.building.no_filter")
                : Component.translatable("siftec.building.filter", c.filter.getHoverName()));
            case "miner", "portable_miner" -> {
                NodeAt node = nodeAt(level, c.node);
                parts.add(node == null ? Component.translatable("siftec.building.no_node")
                    : Component.translatable("siftec.building.node", NodeBlock.label(node.look().type(), node.purity()), formatRate(def.breakTicks() / 20f)));
                if (def.fortune() > 0) parts.add(Component.translatable("siftec.building.fortune", def.fortune()));
                if (node != null) parts.add(Component.translatable("siftec.building.mined", c.mined));
            }
            case "storage" -> {
                int used = 0, total = 0;
                BlockPos vault = cell(c, create("item_vault"));
                Storage<ItemVariant> s = vault == null ? null : ItemStorage.SIDED.find(level, vault, null);
                if (s != null) {
                    for (StorageView<ItemVariant> v : s) {
                        total++;
                        if (!v.isResourceBlank()) used++;
                    }
                }
                parts.add(Component.translatable("siftec.building.storage", used, total));
            }
            case "truck_station" -> TruckStations.status(level, c, parts);
            case "drone_port" -> {
                if (SpecialBuildings.droneTerminal(level, c) instanceof DronePortBlockEntity port) {
                    parts.add(Component.translatable(port.statusKey(), port.chargesNeeded()));
                }
            }
            default -> {
            }
        }
        ProductionLogic.status(level, c, def, parts);
        ExtractorLogic.status(level, c, def, parts);
        if (!def.logic().equals("storage") && !def.logic().isEmpty() && !def.logic().equals("power") && !def.logic().contains("extractor")
            && !def.logic().equals("pressurizer") && !Set.of("workshop", "shop", "radar", "truck_station", "drone_port", "foundation", "designer").contains(def.logic())) {
            parts.add(Component.translatable("siftec.building.made", c.made));
        }
        BuildingPower.status(level, c, def, parts);
        if (!c.problem.isEmpty()) parts.add(Component.translatable(c.problem));
        Component line = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) line = line.copy().append(", ");
            line = line.copy().append(parts.get(i));
        }
        return Component.translatable("siftec.building.status", def.name(), line);
    }

    private static String formatRate(float perMinute) {
        return perMinute == Math.rint(perMinute) ? String.valueOf((int) perMinute) : String.valueOf(perMinute);
    }
}
