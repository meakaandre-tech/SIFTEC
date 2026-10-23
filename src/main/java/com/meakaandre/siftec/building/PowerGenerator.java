package com.meakaandre.siftec.building;

import com.meakaandre.siftec.geyser.GeyserPurity;
import com.meakaandre.siftec.mixin.building.BlazeBurnerAccessor;
import com.meakaandre.siftec.power.GridStats;
import com.meakaandre.siftec.power.Power;
import com.meakaandre.siftec.power.PowerMember;
import com.meakaandre.siftec.sim.Keys;
import com.meakaandre.siftec.util.Chunks;
import com.meakaandre.siftec.util.Transfers;
import com.zurrtum.create.content.processing.burner.BlazeBurnerBlockEntity;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import org.antarcticgardens.cna.content.nuclear.reactor.rod.ReactorRodBlockEntity;

import java.util.List;
import java.util.function.Function;

/**
 * A generator: its machines make the power ({@link Generators}), and it offers the grid what its coils' carbon brushes
 * hold this tick, no more. What the grid draws comes out of the brushes, and the share of it (the load) sets how fast
 * its fuel burns. Nothing is ever made here: with its machines stopped or broken it offers nothing.
 */
final class PowerGenerator extends FuseHolder {
    final ServerLevel level;
    final BuildingDef def;
    final double full;

    PowerGenerator(ServerLevel level, ControllerBlockEntity c, BuildingDef def) {
        super(c);
        this.level = level;
        this.def = def;
        this.full = Power.su(def.mw());
    }

    @Override
    public boolean boostable() {
        return true;
    }

    @Override
    public double capacity() {
        if (c.isRemoved() || c.tripped || c.broken) {
            c.offered = 0;
            return 0;
        }
        double su = Generators.offered(level, c);
        c.offered = su;
        c.problem = su > 0 ? "" : c.machineProblem.isEmpty() ? "siftec.power.starting" : c.machineProblem;
        return su;
    }

    /** Its machines have their fuel (and water) but make nothing yet: a boiler heating up, an engine spinning up. */
    @Override
    public boolean starting() {
        boolean ready = !c.isRemoved() && !c.tripped && !c.broken && c.offered <= 0 && c.machineProblem.isEmpty();
        // only for a few seconds after it last became ready: a machine stuck "starting" must not hold the fuse for ever
        long[] w = PowerMember.startWindow(ready, c.startingSince, level.getGameTime());
        c.startingSince = w[0];
        return w[1] == 1;
    }

    @Override
    public void generated(double su) {
        c.loadAt = level.getGameTime();
        c.load = c.offered > 0 ? (float) Math.min(1, Math.max(0, su / c.offered)) : 0;
        c.lastGenerated = su;
        if (su <= 0) return;
        Generators.take(level, c, su);
        c.energyGiven += su;
        if (def.power() == PowerRole.GEOTHERMAL) {
            c.fluidOwed += BuildingPower.ACID_PER_SECOND / 20.0;
            long mb = (long) c.fluidOwed;
            if (mb > 0) {
                BuildingPower.fill(level, BuildingPower.tank(c), "sulfuric_acid", mb);
                c.fluidOwed -= mb;
            }
        }
    }

    @Override
    public void grid(GridStats stats) {
        super.grid(stats);
        if (stats.tripped || stats.produced <= 0) c.lastGenerated = 0;
    }

    /** SU-ticks of fuel it holds: in its machines (burner fuel, the engine's tank, the rods) and at its input. */
    @Override
    public double reserve() {
        return reserve(pos -> Chunks.blockEntity(level, pos));
    }

    /**
     * The same, reading its machines through {@code at} (a chunk that is unloading hands its block entities out only
     * from itself, not through the level).
     */
    double reserve(Function<BlockPos, BlockEntity> at) {
        PowerRole kind = def.power();
        if (kind == PowerRole.GEOTHERMAL) return Double.POSITIVE_INFINITY;
        double r = 0;
        switch (kind) {
            case BIOMASS, COAL -> {
                List<BlockPos> burners = BuildingLogic.cells(c, Generators.block("create:blaze_burner"));
                for (BlockPos pos : burners) {
                    if (at.apply(pos) instanceof BlazeBurnerBlockEntity b) {
                        r += full / Math.max(1, burners.size()) * b.getRemainingBurnTime();
                    }
                }
            }
            case FUEL -> {
                BlockPos engine = BuildingLogic.cell(c, Generators.block("createdieselgenerators:diesel_engine"));
                if (engine != null && at.apply(engine) instanceof EngineControl e && !e.siftec$fuel().isEmpty()) {
                    r += Power.suTicksOfMj(BuildingPower.fluidMj(kind, e.siftec$fuel().getFluid()) * e.siftec$fuel().getAmount() / (FluidConstants.BUCKET / 1000.0));
                }
            }
            case NUCLEAR -> {
                int rods = Math.max(1, BuildingLogic.cells(c, Generators.block("create_new_age:reactor_rod")).size());
                r += full * Generators.rodFuel(c, at) / (double) rods;
            }
            default -> {
            }
        }
        ItemStack in = c.input.getItem(0);
        r += Power.suTicksOfMj(BuildingPower.itemMj(kind, in) * in.getCount());
        return r;
    }

    /** Away, a Geothermal Generator gives its geyser's average, not whatever part of its swing it was at. */
    @Override
    public double steady(double capacity) {
        if (def.power() != PowerRole.GEOTHERMAL) return capacity;
        BlockPos vent = BuildingPower.vent(level, c);
        if (vent == null || c.problem.equals("siftec.power.acid_full")) return 0;
        return Power.su(switch (GeyserPurity.of(level, vent)) {
            case IMPURE -> 100.0;
            case PURE -> 400.0;
            default -> 200.0;
        });
    }

    /** Gives back energy the factory simulation took from its input but did not burn ({@link #refund(ServerLevel, ControllerBlockEntity, BuildingDef, double, String)}). */
    void refund(double suTicks, String lastFuel) {
        PowerGenerator.refund(level, c, def, suTicks, lastFuel);
    }

    /** Burns what it gave while it was away: out of its machines' fuel first, then its input. */
    @Override
    public void spend(double suTicks) {
        PowerRole kind = def.power();
        if (suTicks <= 0 || kind == PowerRole.GEOTHERMAL) return;
        double left = suTicks;
        switch (kind) {
            case BIOMASS, COAL -> {
                List<BlockPos> burners = BuildingLogic.cells(c, Generators.block("create:blaze_burner"));
                double per = full / Math.max(1, burners.size());
                for (BlockPos pos : burners) {
                    if (left <= 0) break;
                    if (!(Chunks.blockEntity(level, pos) instanceof BlazeBurnerBlockEntity b)) continue;
                    int ticks = (int) Math.min(b.getRemainingBurnTime(), Math.ceil(left / per));
                    ((BlazeBurnerAccessor) b).siftec$setRemainingBurnTime(b.getRemainingBurnTime() - ticks);
                    left -= ticks * per;
                }
            }
            case FUEL -> {
                BlockPos at = BuildingLogic.cell(c, Generators.block("createdieselgenerators:diesel_engine"));
                if (at != null && Chunks.blockEntity(level, at) instanceof EngineControl e && !e.siftec$fuel().isEmpty()) {
                    double perMb = BuildingPower.fluidMj(kind, e.siftec$fuel().getFluid());
                    if (perMb > 0) {
                        long mb = (long) Math.ceil(left / Power.suTicksOfMj(perMb));
                        long took = drainAny(level, at, mb);
                        left -= Power.suTicksOfMj(perMb * took);
                    }
                }
            }
            case NUCLEAR -> {
                // the rods burn a tick of fuel each for every tick at full power
                long ticks = (long) Math.ceil(left / full);
                for (BlockPos pos : BuildingLogic.cells(c, Generators.block("create_new_age:reactor_rod"))) {
                    if (Chunks.blockEntity(level, pos) instanceof ReactorRodBlockEntity rod) {
                        rod.fuel = (int) Math.max(0, rod.fuel - ticks);
                        rod.setChanged();
                    }
                }
                left = 0;
            }
            default -> {
            }
        }
        while (left > 0) {
            ItemStack in = c.input.getItem(0);
            double mj = BuildingPower.itemMj(kind, in);
            if (mj <= 0) break;
            in.shrink(1);
            c.input.setChanged();
            c.made++;
            left -= Power.suTicksOfMj(mj);
        }
        c.setChanged();
    }

    /**
     * Puts energy back into a generator's machines (SU-ticks): the unburnt part of the last fuel the factory simulation
     * took from its input while it was away. Into its Blaze Burners as burn time, its engine as the fuel it burns (or
     * the simulation's last fuel), its reactor rods as fuel ticks.
     */
    static void refund(ServerLevel level, ControllerBlockEntity c, BuildingDef def, double suTicks, String lastFuel) {
        if (suTicks <= 0) return;
        double full = Power.su(def.mw());
        switch (def.power()) {
            case BIOMASS, COAL -> {
                List<BlockPos> burners = BuildingLogic.cells(c, Generators.block("create:blaze_burner"));
                double per = full / Math.max(1, burners.size());
                if (per <= 0 || burners.isEmpty()) return;
                int each = (int) Math.min(Integer.MAX_VALUE / 2, Math.floor(suTicks / per / burners.size()));
                for (BlockPos pos : burners) {
                    if (each <= 0 || !(Chunks.blockEntity(level, pos) instanceof BlazeBurnerBlockEntity b)) continue;
                    var access = (BlazeBurnerAccessor) b;
                    if (b.getRemainingBurnTime() <= 0) access.siftec$setActiveFuel(BlazeBurnerBlockEntity.FuelType.NORMAL);
                    access.siftec$setRemainingBurnTime(b.getRemainingBurnTime() + each);
                    b.setChanged();
                }
            }
            case FUEL -> {
                BlockPos at = BuildingLogic.cell(c, Generators.block("createdieselgenerators:diesel_engine"));
                if (at == null || !(Chunks.blockEntity(level, at) instanceof EngineControl e)) return;
                Fluid fluid = e.siftec$fuel().isEmpty() ? Keys.fluidOf(lastFuel) : e.siftec$fuel().getFluid();
                double perMb = BuildingPower.fluidMj(PowerRole.FUEL, fluid);
                Storage<FluidVariant> tank = ProductionLogic.fluids(level, at, null);
                if (perMb <= 0 || tank == null) return;
                long mb = (long) Math.floor(suTicks / Power.suTicksOfMj(perMb));
                if (mb <= 0) return;
                Transfers.insert(tank, FluidVariant.of(fluid), mb * ProductionLogic.MB);
            }
            case NUCLEAR -> {
                if (full <= 0) return;
                int ticks = (int) Math.min(Integer.MAX_VALUE / 2, Math.floor(suTicks / full));
                if (ticks <= 0) return;
                for (BlockPos pos : BuildingLogic.cells(c, Generators.block("create_new_age:reactor_rod"))) {
                    if (Chunks.blockEntity(level, pos) instanceof ReactorRodBlockEntity rod) {
                        rod.fuel = (int) Math.min(ReactorRodBlockEntity.MAX_FUEL, (long) rod.fuel + ticks);
                        rod.setChanged();
                    }
                }
            }
            default -> {
            }
        }
        c.setChanged();
    }

    /** Drains mB of whatever fluid a block's tank holds; how many mB came out. */
    private static long drainAny(Level level, BlockPos pos, long mb) {
        Storage<FluidVariant> tank = ProductionLogic.fluids(level, pos, null);
        if (tank == null) return 0;
        long took = 0;
        try (Transaction tx = Transaction.openOuter()) {
            for (StorageView<FluidVariant> view : tank) {
                if (view.isResourceBlank()) continue;
                took += tank.extract(view.getResource(), (mb - took) * ProductionLogic.MB, tx) / ProductionLogic.MB;
                if (took >= mb) break;
            }
            tx.commit();
        }
        return took;
    }
}
