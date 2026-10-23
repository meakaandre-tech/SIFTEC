package com.meakaandre.siftec.prefab;

import com.meakaandre.siftec.block.Facing;
import com.meakaandre.siftec.building.BoilerWater;
import com.meakaandre.siftec.building.BuildingPower;
import com.meakaandre.siftec.building.BurnerLoad;
import com.meakaandre.siftec.building.PowerRole;
import com.meakaandre.siftec.mixin.building.BlazeBurnerAccessor;
import com.meakaandre.siftec.power.GridStats;
import com.meakaandre.siftec.power.Power;
import com.meakaandre.siftec.power.PowerMember;
import com.zurrtum.create.api.connectivity.ConnectivityHandler;
import com.zurrtum.create.content.fluids.tank.FluidTankBlockEntity;
import com.zurrtum.create.content.processing.burner.BlazeBurnerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.antarcticgardens.cna.content.electricity.generation.brushes.CarbonBrushesBlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * One of the HUB's two built-in Biomass Burners (Satisfactory's HUB Biomass Burners), 30 MW, built as the Biomass Burner
 * building is: behind this block (the fuel inlet in the housing's front wall) a Blaze Burner heats a closed Create
 * boiler, whose Steam Engine turns a Create: New Age generator coil; the coil's energy collects in its carbon brushes,
 * and the grid of the wire connector on this block takes what it uses out of them. This block puts the next fuel into
 * the Blaze Burner as it burns low, at Satisfactory's megajoules, and the share of the burner's power the grid draws
 * (the load) sets how fast that fuel burns, as in Satisfactory. It holds the grid's fuse like any generator: sneak-using
 * it resets a blown one.
 */
public class HubBurnerBlockEntity extends BlockEntity implements PowerMember {
    public static final double MW = 30;
    /** Burn ticks the Blaze Burner has left when the next fuel goes in. */
    private static final int REFILL = 20;

    /**
     * The machine behind the inlet, as the Biomass Burner building's: x across from this block, y up from the deck, z back
     * from the front wall, and which of {@link #MACHINE_STATES} stands there.
     */
    public static final int[][] MACHINE = {
        {1, 1, 5, 0}, {1, 2, 5, 1}, {1, 3, 5, 1}, {1, 4, 5, 1}, {1, 5, 5, 1}, {1, 5, 4, 2}, {1, 5, 3, 9}, {1, 5, 2, 3}, {0, 5, 2, 4},
        {-1, 5, 2, 5},
        // the coil's ring: its four netherite magnets, the rest iron
        {-1, 3, 2, 6}, {-1, 7, 2, 6}, {-1, 5, 0, 6}, {-1, 5, 4, 6},
        {-1, 3, 1, 7}, {-1, 3, 3, 7}, {-1, 7, 1, 7}, {-1, 7, 3, 7}, {-1, 4, 0, 7}, {-1, 6, 0, 7}, {-1, 4, 4, 7}, {-1, 6, 4, 7},
        {-2, 5, 2, 8}};
    public static final String[] MACHINE_STATES = {
        "create:blaze_burner[blaze=smouldering,facing=south]", "create:fluid_tank[bottom=true,shape=window,top=true]",
        "create:steam_engine[face=wall,facing=south,waterlogged=false]", "create:powered_shaft[axis=x]", "create:shaft[axis=x]",
        "create_new_age:generator_coil[axis=x]", "create_new_age:netherite_magnet", "minecraft:iron_block", "create_new_age:carbon_brushes[facing=east]",
        "minecraft:air"};
    private static final int[] BLAZE = {1, 1, 5}, TANK = {1, 2, 5}, BRUSHES = {-2, 5, 2};

    public final SimpleContainer fuel = new SimpleContainer(1) {
        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return accepts(stack);
        }

        @Override
        public void setChanged() {
            super.setChanged();
            HubBurnerBlockEntity.this.setChanged();
        }
    };
    /** SU-ticks of fuel burning in its Blaze Burner (for the status line and the selftests). */
    public double energy;
    public boolean tripped;
    /** SU it gave on its last tick, what its brushes offered, and the share of that the grid took. */
    public double lastGenerated, offered;
    private float load;
    private long loadAt;
    private boolean formed;
    public @Nullable GridStats grid;

    public HubBurnerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public static boolean accepts(ItemStack stack) {
        return BuildingPower.itemMj(PowerRole.BIOMASS, stack) > 0;
    }

    public void addFuel(ItemStack held) {
        if (!accepts(held)) return;
        ItemStack inside = fuel.getItem(0);
        if (inside.isEmpty()) {
            fuel.setItem(0, held.copyAndClear());
        } else if (ItemStack.isSameItemSameComponents(inside, held)) {
            int moved = Math.min(held.getCount(), inside.getMaxStackSize() - inside.getCount());
            inside.grow(moved);
            held.shrink(moved);
            fuel.setChanged();
        }
    }

    // ---- the machine behind it

    /** A cell of its machine in the world: across, up from the deck and back, turned as the HUB is. */
    public BlockPos part(int x, int y, int z) {
        BlockState state = getBlockState();
        Direction in = state.hasProperty(Facing.FACING) ? state.getValue(Facing.FACING).getOpposite() : Direction.NORTH;
        Direction right = in.getClockWise();
        return worldPosition.offset(right.getStepX() * x + in.getStepX() * z, y - 1, right.getStepZ() * x + in.getStepZ() * z);
    }

    private @Nullable BlazeBurnerBlockEntity blaze() {
        return level != null && level.getBlockEntity(part(BLAZE[0], BLAZE[1], BLAZE[2])) instanceof BlazeBurnerBlockEntity b ? b : null;
    }

    private @Nullable CarbonBrushesBlockEntity brushes() {
        return level != null && level.getBlockEntity(part(BRUSHES[0], BRUSHES[1], BRUSHES[2])) instanceof CarbonBrushesBlockEntity b ? b : null;
    }

    /** Its burner's fuel goes in as it burns low, its boiler is told it is a closed loop, its tank is one boiler. */
    public void serverTick() {
        if (!(level instanceof ServerLevel server)) return;
        if (level.getBlockEntity(part(TANK[0], TANK[1], TANK[2])) instanceof FluidTankBlockEntity tank) {
            if (!formed && tank.isController() && tank.getHeight() == 1) ConnectivityHandler.formMulti(tank);
            formed = true;
            FluidTankBlockEntity boiler = tank.getControllerBE();
            if (boiler != null) {
                // a closed boiler: it needs no water, as Satisfactory's burner takes none
                ((BoilerWater) boiler.boiler).siftec$waterPerLevel(0);
                if (server.getGameTime() % 20 == 0) {
                    boiler.updateBoilerState();
                    boiler.updateBoilerTemperature();
                }
            }
        }
        BlazeBurnerBlockEntity burner = blaze();
        if (burner == null) return;
        float now = tripped || loadAt < server.getGameTime() - 1 ? 0 : load;
        ((BurnerLoad) burner).siftec$load(now);
        boolean out = burner.getActiveFuel() == BlazeBurnerBlockEntity.FuelType.NONE;
        if (!tripped && (out || burner.getRemainingBurnTime() <= REFILL)) {
            ItemStack in = fuel.getItem(0);
            double mj = BuildingPower.itemMj(PowerRole.BIOMASS, in);
            if (mj > 0) {
                // the next fuel: its Satisfactory megajoules, at 30 MW
                int ticks = (int) Math.max(1, Math.round(mj / MW * 20));
                in.shrink(1);
                fuel.setChanged();
                BlazeBurnerAccessor acc = (BlazeBurnerAccessor) burner;
                acc.siftec$setActiveFuel(BlazeBurnerBlockEntity.FuelType.NORMAL);
                acc.siftec$setRemainingBurnTime((out ? 0 : burner.getRemainingBurnTime()) + ticks);
                burner.updateBlockState();
            }
        }
        energy = burner.getActiveFuel() == BlazeBurnerBlockEntity.FuelType.NONE ? 0 : Power.su(MW) * burner.getRemainingBurnTime();
    }

    // ---- on the grid

    @Override
    public double capacity() {
        CarbonBrushesBlockEntity b = brushes();
        if (isRemoved() || tripped || b == null) {
            offered = 0;
            return 0;
        }
        offered = Power.suOfFe(Math.min(b.getEnergyStorage().getStoredEnergy(), b.getLastOutput()));
        return offered;
    }

    @Override
    public boolean starting() {
        BlazeBurnerBlockEntity burner = blaze();
        boolean ready = !isRemoved() && !tripped && offered <= 0 && burner != null && burner.getActiveFuel() != BlazeBurnerBlockEntity.FuelType.NONE;
        // only for a few seconds after it last became ready (a burner missing its brushes never gets going)
        long[] w = PowerMember.startWindow(ready, startingSince, level == null ? 0 : level.getGameTime());
        startingSince = w[0];
        return w[1] == 1;
    }

    /** The game time it last became ready to give power while giving none (PowerMember#startWindow). */
    private long startingSince = Long.MIN_VALUE;

    @Override
    public boolean boostable() {
        return true;
    }

    @Override
    public void generated(double su) {
        if (level != null) loadAt = level.getGameTime();
        load = offered > 0 ? (float) Math.min(1, Math.max(0, su / offered)) : 0;
        lastGenerated = su;
        CarbonBrushesBlockEntity b = brushes();
        if (su > 0 && b != null) b.getEnergyStorage().internalExtract(Math.round(Power.feOfSu(su)), false);
        setChanged();
    }

    @Override
    public boolean holdsFuse() {
        return true;
    }

    @Override
    public boolean gone() {
        return isRemoved();
    }

    /** SU-ticks of fuel it holds: what its Blaze Burner is burning, and what waits at the inlet. */
    @Override
    public double reserve() {
        ItemStack in = fuel.getItem(0);
        BlazeBurnerBlockEntity burner = blaze();
        double burning = burner == null ? 0 : Power.su(MW) * burner.getRemainingBurnTime();
        return burning + Power.suTicksOfMj(BuildingPower.itemMj(PowerRole.BIOMASS, in) * in.getCount());
    }

    /** Burns what it gave while away: out of its Blaze Burner first, then the inlet. */
    @Override
    public void spend(double suTicks) {
        if (suTicks <= 0) return;
        double per = Power.su(MW), left = suTicks;
        BlazeBurnerBlockEntity burner = blaze();
        if (burner != null) {
            int ticks = (int) Math.min(burner.getRemainingBurnTime(), Math.ceil(left / per));
            ((BlazeBurnerAccessor) burner).siftec$setRemainingBurnTime(burner.getRemainingBurnTime() - ticks);
            left -= ticks * per;
        }
        ItemStack in = fuel.getItem(0);
        double mj = BuildingPower.itemMj(PowerRole.BIOMASS, in);
        while (left > 0 && mj > 0 && !in.isEmpty()) {
            in.shrink(1);
            left -= Power.suTicksOfMj(mj);
        }
        fuel.setChanged();
    }

    @Override
    public boolean tripped() {
        return tripped;
    }

    @Override
    public void trip(boolean tripped) {
        if (this.tripped != tripped) {
            this.tripped = tripped;
            setChanged();
        }
    }

    @Override
    public void grid(GridStats stats) {
        grid = stats;
        if (stats.tripped || !stats.powered) lastGenerated = 0;
    }

    /** "Biomass Burner: gives 6,144 of 6,144 SU, 12 fuel left; grid ..." */
    public Component status() {
        BlazeBurnerBlockEntity burner = blaze();
        boolean lit = burner != null && burner.getActiveFuel() != BlazeBurnerBlockEntity.FuelType.NONE;
        Component head = Component.translatable(tripped ? "siftec.burner.tripped" : fuel.getItem(0).isEmpty() && !lit ? "siftec.burner.no_fuel" : "siftec.burner.running",
            Power.su((float) lastGenerated), Power.su((float) Power.su(MW)), fuel.getItem(0).getCount());
        return grid == null || tripped ? head : head.copy().append(", ").append(grid.line());
    }

    @Override
    protected void saveAdditional(ValueOutput view) {
        super.saveAdditional(view);
        view.store("Fuel", ItemStack.OPTIONAL_CODEC, fuel.getItem(0));
        view.putBoolean("Tripped", tripped);
    }

    @Override
    protected void loadAdditional(ValueInput view) {
        super.loadAdditional(view);
        fuel.setItem(0, view.read("Fuel", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
        tripped = view.getBooleanOr("Tripped", false);
    }
}
