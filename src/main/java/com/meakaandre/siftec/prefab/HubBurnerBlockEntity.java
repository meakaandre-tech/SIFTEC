package com.meakaandre.siftec.prefab;

import com.meakaandre.siftec.building.BuildingPower;
import com.meakaandre.siftec.power.GridStats;
import com.meakaandre.siftec.power.Power;
import com.meakaandre.siftec.power.PowerMember;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * One of the HUB's Biomass Burners (Satisfactory's HUB Biomass Burners): a 30 MW generator on the grid of the wire
 * connector on top of it. It burns leaves, logs, Biomass and Solid Biofuel put into it by hand, hopper, funnel or chute
 * at Satisfactory's energy, for only what the grid draws. It holds the grid's fuse like any generator: sneak-using it
 * resets a blown one.
 */
public class HubBurnerBlockEntity extends BlockEntity implements PowerMember {
    public static final double MW = 30;

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
    /** Banked energy of what it last took, in SU-ticks. */
    public double energy;
    public boolean tripped;
    /** SU it gave on its last tick. */
    public double lastGenerated;
    public @Nullable GridStats grid;

    public HubBurnerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public static boolean accepts(ItemStack stack) {
        return BuildingPower.itemMj("biomass", stack) > 0;
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

    // ---- on the grid

    @Override
    public double capacity() {
        if (isRemoved() || tripped) return 0;
        double full = Power.su(MW);
        if (energy < full) {
            ItemStack in = fuel.getItem(0);
            double mj = BuildingPower.itemMj("biomass", in);
            if (mj > 0) {
                in.shrink(1);
                fuel.setChanged();
                energy += Power.suTicksOfMj(mj);
            }
        }
        return Math.min(full, energy);
    }

    @Override
    public boolean boostable() {
        return true;
    }

    @Override
    public void generated(double su) {
        energy = Math.max(0, energy - su);
        lastGenerated = su;
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

    @Override
    public double reserve() {
        ItemStack in = fuel.getItem(0);
        return Math.max(0, energy) + Power.suTicksOfMj(BuildingPower.itemMj("biomass", in) * in.getCount());
    }

    @Override
    public void spend(double suTicks) {
        if (suTicks <= 0) return;
        energy -= suTicks;
        ItemStack in = fuel.getItem(0);
        double mj = BuildingPower.itemMj("biomass", in);
        while (energy < 0 && mj > 0 && !in.isEmpty()) {
            in.shrink(1);
            energy += Power.suTicksOfMj(mj);
        }
        energy = Math.max(0, energy);
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
        Component head = Component.translatable(tripped ? "siftec.burner.tripped" : fuel.getItem(0).isEmpty() && energy <= 0 ? "siftec.burner.no_fuel" : "siftec.burner.running",
            Power.su((float) lastGenerated), Power.su((float) Power.su(MW)), fuel.getItem(0).getCount());
        return grid == null || tripped ? head : head.copy().append(", ").append(grid.line());
    }

    @Override
    protected void saveAdditional(ValueOutput view) {
        super.saveAdditional(view);
        view.store("Fuel", ItemStack.OPTIONAL_CODEC, fuel.getItem(0));
        view.putDouble("Energy", energy);
        view.putBoolean("Tripped", tripped);
    }

    @Override
    protected void loadAdditional(ValueInput view) {
        super.loadAdditional(view);
        fuel.setItem(0, view.read("Fuel", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
        energy = view.getDoubleOr("Energy", 0);
        tripped = view.getBooleanOr("Tripped", false);
    }
}
