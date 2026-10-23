package com.meakaandre.siftec.building;

import com.meakaandre.siftec.power.GridStats;
import com.meakaandre.siftec.power.PowerMember;
import com.meakaandre.siftec.sim.Factories;
import com.meakaandre.siftec.util.Transfers;
import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.FilteringStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A building's controller: which building it is, where and how it was placed, the company that owns it, and what
 * dismantling gives back (the build cost that was paid). Once a second it checks that every block of the building
 * is still there; if one has gone anyway (a command, another mod), the whole building comes down and the refund
 * and contents drop where the controller stood.
 * <p>
 * It also runs the building ({@link BuildingLogic}): its recipe filter, the pace its machines keep, the node it
 * stands on, and the input and output slots its ports reach when the machines inside have none of their own (a
 * miner's ore, the Smelter's ore and ingots).
 */
public class ControllerBlockEntity extends BlockEntity {
    /** Slots of a Nuclear Power Plant's waste store, beside its output slot. */
    private static final int WASTE_SLOTS = 8;
    /** Input slots of the Particle Accelerator's and the Converter's ingredient buffer. */
    private static final int BUFFER_SLOTS = 9;
    /** Ticks between tries to check an unindexed building's blocks against its shape. */
    private static final int REPAIR_RETRY_TICKS = 20;
    public String buildingId = "";
    public @Nullable BlockPos anchor;
    public Direction facing = Direction.NORTH;
    public boolean flip;
    public String companyId = "";
    public final List<ItemStack> refund = new ArrayList<>();
    /** The recipe filter: what the building makes (empty: anything its machines can). */
    public ItemStack filter = ItemStack.EMPTY;
    /** A node building's node. */
    public @Nullable BlockPos node;
    /** The game time before which the machines may not start their next recipe (Satisfactory's time per recipe). */
    public long nextCraft;
    /**
     * The Manufacturer's and the Quantum Encoder's assembly line: the game time before which the next item may not go onto
     * it. The Mixer making the Core keeps its own time ({@link #nextCraft}), so the two run side by side, each at
     * Satisfactory's rate.
     */
    public long lineNext;
    /** How many recipes the building has started (or how many times a miner has broken its node), for the status line and the selftests. */
    public long made;
    /** The Portable Miner's ticks into breaking its node. */
    public float progress;
    /** Items a miner has got from its node (every block it broke, with its Fortune). */
    public long mined;
    /** How the Customizer has painted the building ({@link Paint}; "" as built). */
    public String paint = "";
    /** The game time the Radar Tower last swept its range for crash sites. */
    public long lastSweep;
    /** A Truck Station's mode: unloading vehicles into its storage (true) or loading them from it (false). */
    public boolean unload;
    /** What a status line says is wrong right now ("" if nothing), as a lang key. */
    public String problem = "";
    public final SimpleContainer input = slots(), output = slots();
    /**
     * A Nuclear Power Plant's waste beyond its output slot: with the output slot it holds
     * {@link BuildingPower#WASTE_ROOM} waste, several rods' worth as Satisfactory's plant does, so the plant keeps
     * refuelling while its waste waits to be carried away. Its output port takes from it after the output slot.
     */
    public final SimpleContainer waste = slots(WASTE_SLOTS);
    /**
     * Nine input slots, for the Particle Accelerator's and the Converter's ingredients: slot i takes the recipe's input i
     * mod n, so a plentiful input never fills the slots a scarce one needs.
     */
    public final SimpleContainer buffer = new SimpleContainer(BUFFER_SLOTS) {
        @Override
        public void setChanged() {
            super.setChanged();
            ControllerBlockEntity.this.setChanged();
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return ProductionLogic.processorSlotTakes(ControllerBlockEntity.this, slot, stack);
        }
    };
    /** What the assembly line last put on each of its stations (a Depot or the Item Drain), to see when its step is done. */
    public final List<ItemStack> line = new ArrayList<>();
    // ---- power (BuildingPower)
    /** Banked energy in SU-ticks: a generator's fuel, a Power Storage's charge. */
    public double energy;
    /** Water owed by a generator, or acid made by the Geothermal Generator, in mB not yet drawn or put in its tank. */
    public double fluidOwed;
    /** This building's grid's fuse is blown (generators and Power Storage hold it). */
    public boolean tripped;
    /** A generator is giving power (its fires are lit, its turbine turns); the geyser under it is erupting. */
    public boolean running, erupting;
    /** Ticks an Alien Power Augmenter is still fed by its last Alien Power Matrix. */
    public int fedTicks;
    /** A consumer had power on its last tick (while idle, its standby draw). */
    public boolean powered;
    /**
     * The factory simulation runs this building (its chunks stopped ticking) and has not handed it back yet: its ports
     * pass nothing, and it does not tick, until it is caught up ({@link Factories#awake}).
     */
    public boolean simulated;
    /** The game time it last ran for real (a gap means it was not ticking, and may have to be caught up first). */
    public long simTicked = Long.MIN_VALUE;
    /** A consumer has nothing to work on, so it draws only its standby power ({@link BuildingLogic#working}). Worked out each tick. */
    public boolean idle;
    /** The game time {@link #idle} was last worked out at, and until when the building counts as working after its last work. */
    long idleAt = Long.MIN_VALUE, awakeUntil;
    /** SU a generator gave on its last tick (a Power Storage: SU-ticks it took, negative when it gave). */
    public double lastGenerated;
    /** A generator: SU its machines offered the grid this tick, and the share of it the grid drew (and the tick it did). */
    public double offered;
    public float load;
    public long loadAt = Long.MIN_VALUE;
    /** A generator: its load over the last ticks (its boilers' water follows the least of them), water owed and given (droplets). */
    final float[] loadWindow = new float[Generators.WATER_WINDOW];
    public double waterOwed;
    /** A generator: the game time it last became ready to give power while giving none (PowerMember#startWindow). */
    public long startingSince = Long.MIN_VALUE;
    public long waterUsed;
    /** What its machines say is wrong ("" if nothing), as a lang key: a generator's fuel, water, geyser. */
    public String machineProblem = "";
    /** SU-ticks a generator has given since it loaded, and waste a nuclear plant has given out (for the selftests). */
    public double energyGiven;
    public long wasteMade;
    /** Cores the Particle Accelerator has dropped into its fan's column (for the selftests). */
    public long launched;
    /** A Particle Accelerator or Converter: the SU it draws this tick (Satisfactory's power over its recipe's cycle). */
    public double demandSu;
    /** The Converter's cell, while it is neither on the Depot, in the Deployer's hand nor in the Item Drain. */
    public ItemStack carrier = ItemStack.EMPTY;
    /** The template its blocks were last checked against ({@link BuildingRepair}); 0 for a building from before. */
    public int shape;
    /** Its machines are not all there and cannot be rebuilt yet (something stands where they go): it does nothing. */
    public boolean broken;
    /** Its grid as last balanced, and its part in it. */
    public @Nullable GridStats grid;
    @Nullable PowerMember member;
    @Nullable BuildingDef memberFor;
    /** Set while the building is taken down on purpose, so the controller's own removal does not count as breaking. */
    boolean removing;
    private int ticks;
    private boolean indexed;
    /** A foundation has looked over its blocks since it loaded (after that, only when one changes). */
    private boolean membersChecked;
    private long repairTried;
    private @Nullable Storage<ItemVariant> storage;

    public ControllerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    private SimpleContainer slots() {
        return slots(1);
    }

    private SimpleContainer slots(int size) {
        return new SimpleContainer(size) {
            @Override
            public void setChanged() {
                super.setChanged();
                ControllerBlockEntity.this.setChanged();
            }
        };
    }

    public @Nullable BuildingDef def() {
        return Buildings.def(buildingId);
    }

    public @Nullable Placement placement() {
        BuildingDef def = Buildings.def(buildingId);
        if (def == null || anchor == null) return null;
        return new Placement(def, anchor, facing, flip);
    }

    /**
     * What the ports see when they point at the controller: its input slot takes what the building accepts, its
     * output slot gives what it has made. Only the building's ports reach it ({@link PortBlockEntity#items}); the
     * controller has no item storage on any of its sides, as a Satisfactory machine is fed only at its ports.
     */
    public Storage<ItemVariant> storage() {
        if (storage == null) {
            Storage<ItemVariant> in = new FilteringStorage<>(ContainerStorage.of(input, null)) {
                @Override
                protected boolean canInsert(ItemVariant resource) {
                    return BuildingLogic.accepts(ControllerBlockEntity.this, resource.toStack());
                }

                @Override
                protected boolean canExtract(ItemVariant resource) {
                    return false;
                }
            };
            storage = new CombinedStorage<>(List.of(in, FilteringStorage.insertOnlyOf(ContainerStorage.of(buffer, null)),
                FilteringStorage.extractOnlyOf(ContainerStorage.of(output, null)), FilteringStorage.extractOnlyOf(ContainerStorage.of(waste, null))));
        }
        return storage;
    }

    /** Empties the input and output slots and returns what they held (dismantling gives it back). */
    public List<ItemStack> takeContents() {
        List<ItemStack> out = new ArrayList<>();
        for (SimpleContainer c : new SimpleContainer[]{input, output, waste, buffer}) {
            Transfers.takeAll(c, c.getContainerSize(), out);
        }
        setChanged();
        return out;
    }

    public void serverTick() {
        if (!(level instanceof ServerLevel server) || removing) return;
        // what the factory simulation did while its chunks did not tick goes in before it runs for real
        if (!Factories.awake(server, this)) return;
        if (!indexed && (repairTried == 0 || server.getGameTime() - repairTried >= REPAIR_RETRY_TICKS)) {
            // its blocks are checked against its shape (and rebuilt where they differ) before they are listed again
            repairTried = server.getGameTime();
            indexed = BuildingRepair.check(server, this) != BuildingRepair.Result.WAITING;
            if (removing) return;
        }
        // a Foundations-tab piece (a foundation, wall or roof) or a Blueprint Designer runs nothing and makes nothing: it
        // only has to stay whole
        BuildingDef def = def();
        boolean foundation = def != null && def.logic().isFoundation();
        if (!broken && !foundation) BuildingLogic.tick(server, this);
        // the factory simulation reads it now and then, to run it while its chunk does not tick
        if (!broken && indexed && !foundation) Factories.building(server, this);
        if (++ticks % 20 != 0) return;
        // its machines are looked over every few seconds and anything missing is put back; a missing piece of shell
        // brings the building down
        if (indexed && !foundation && ticks % BuildingRepair.EVERY == 0) BuildingRepair.check(server, this);
        if (!indexed || broken || removing) return;
        // a foundation's blocks are looked over only when one of them changed (and once after it loads), not every second
        if (foundation && membersChecked && !Buildings.takeChanged(server, getBlockPos())) return;
        membersChecked = true;
        BlockPos missing = Buildings.missingMember(server, this);
        if (missing != null) Buildings.collapse(server, this, missing);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        // the controller itself went without the Build Gun: the rest of the building follows next tick
        if (!removing && level instanceof ServerLevel server) Buildings.controllerGone(server, this);
    }

    @Override
    protected void saveAdditional(ValueOutput view) {
        super.saveAdditional(view);
        view.putString("Building", buildingId);
        if (anchor != null) view.store("Anchor", BlockPos.CODEC, anchor);
        view.putInt("Facing", facing.get2DDataValue());
        view.putBoolean("Flip", flip);
        view.putString("Company", companyId);
        if (!paint.isEmpty()) view.putString("Paint", paint);
        view.putLong("LastSweep", lastSweep);
        view.store("Refund", ItemStack.OPTIONAL_CODEC.listOf(), new ArrayList<>(refund));
        view.store("Filter", ItemStack.OPTIONAL_CODEC, filter);
        if (node != null) view.store("Node", BlockPos.CODEC, node);
        view.putLong("NextCraft", nextCraft);
        view.putLong("LineNext", lineNext);
        view.putLong("Made", made);
        view.putFloat("Progress", progress);
        view.putLong("Mined", mined);
        view.store("Input", ItemStack.OPTIONAL_CODEC, input.getItem(0));
        view.store("Output", ItemStack.OPTIONAL_CODEC, output.getItem(0));
        view.putDouble("Energy", energy);
        view.putDouble("FluidOwed", fluidOwed);
        view.putBoolean("Tripped", tripped);
        view.putBoolean("Running", running);
        view.putBoolean("Erupting", erupting);
        view.putInt("Fed", fedTicks);
        view.putBoolean("Unload", unload);
        List<ItemStack> held = new ArrayList<>();
        for (int i = 0; i < buffer.getContainerSize(); i++) held.add(buffer.getItem(i));
        view.store("Buffer", ItemStack.OPTIONAL_CODEC.listOf(), held);
        List<ItemStack> wasteHeld = new ArrayList<>();
        for (int i = 0; i < waste.getContainerSize(); i++) wasteHeld.add(waste.getItem(i));
        view.store("Waste", ItemStack.OPTIONAL_CODEC.listOf(), wasteHeld);
        view.store("Line", ItemStack.OPTIONAL_CODEC.listOf(), new ArrayList<>(line));
        view.store("Carrier", ItemStack.OPTIONAL_CODEC, carrier);
        view.putInt("Shape", shape);
    }

    @Override
    protected void loadAdditional(ValueInput view) {
        super.loadAdditional(view);
        buildingId = view.getStringOr("Building", "");
        anchor = view.read("Anchor", BlockPos.CODEC).orElse(null);
        facing = Direction.from2DDataValue(view.getIntOr("Facing", 2));
        flip = view.getBooleanOr("Flip", false);
        companyId = view.getStringOr("Company", "");
        paint = view.getStringOr("Paint", "");
        lastSweep = view.getLongOr("LastSweep", 0L);
        refund.clear();
        for (ItemStack stack : view.read("Refund", ItemStack.OPTIONAL_CODEC.listOf()).orElse(List.of())) if (!stack.isEmpty()) refund.add(stack);
        filter = view.read("Filter", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);
        node = view.read("Node", BlockPos.CODEC).orElse(null);
        nextCraft = view.getLongOr("NextCraft", 0L);
        lineNext = view.getLongOr("LineNext", 0L);
        made = view.getLongOr("Made", 0L);
        progress = view.getFloatOr("Progress", 0f);
        mined = view.getLongOr("Mined", 0L);
        input.setItem(0, view.read("Input", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
        output.setItem(0, view.read("Output", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
        energy = view.getDoubleOr("Energy", 0);
        fluidOwed = view.getDoubleOr("FluidOwed", 0);
        tripped = view.getBooleanOr("Tripped", false);
        running = view.getBooleanOr("Running", false);
        erupting = view.getBooleanOr("Erupting", false);
        fedTicks = view.getIntOr("Fed", 0);
        unload = view.getBooleanOr("Unload", false);
        List<ItemStack> held = view.read("Buffer", ItemStack.OPTIONAL_CODEC.listOf()).orElse(List.of());
        for (int i = 0; i < buffer.getContainerSize(); i++) buffer.setItem(i, i < held.size() ? held.get(i) : ItemStack.EMPTY);
        List<ItemStack> wasteHeld = view.read("Waste", ItemStack.OPTIONAL_CODEC.listOf()).orElse(List.of());
        for (int i = 0; i < waste.getContainerSize(); i++) waste.setItem(i, i < wasteHeld.size() ? wasteHeld.get(i) : ItemStack.EMPTY);
        line.clear();
        line.addAll(view.read("Line", ItemStack.OPTIONAL_CODEC.listOf()).orElse(List.of()));
        carrier = view.read("Carrier", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);
        shape = view.getIntOr("Shape", 0);
    }
}
