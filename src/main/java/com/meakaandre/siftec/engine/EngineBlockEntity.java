package com.meakaandre.siftec.engine;

import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.HubBlock;
import com.meakaandre.siftec.hub.HubBlockEntity;
import com.zurrtum.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.zurrtum.create.foundation.utility.FuelUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public class EngineBlockEntity extends GeneratingKineticBlockEntity {
    public static final float SPEED = 32f;
    /** Stress capacity in SU: against a furnace or smoker, against a blast furnace, and for one HUB Engine. */
    public static final float FURNACE_SU = 1024f, BLAST_SU = 2048f, HUB_SU = 256f;
    private static final int OK = 0, NO_FURNACE = 1, NO_HUB = 2, TOO_MANY = 3, NO_FUEL = 4;

    /** A HUB Engine's fuel. Hoppers, funnels and chutes can fill it. */
    public final SimpleContainer fuel = new SimpleContainer(1) {
        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return FuelUtil.isFuel(stack);
        }

        @Override
        public void setChanged() {
            super.setChanged();
            EngineBlockEntity.this.setChanged();
        }
    };
    private int burn;
    private float capacity;
    private int status = NO_FUEL;
    private int clock;

    public EngineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    private boolean hubKind() {
        return getBlockState().getBlock() instanceof EngineBlock block && block.hub;
    }

    public void addFuel(ItemStack held) {
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

    public String statusKey() {
        return "siftec.engine." + switch (status) {
            case NO_FURNACE -> "no_furnace";
            case NO_HUB -> "no_hub";
            case TOO_MANY -> "too_many";
            case NO_FUEL -> "no_fuel";
            default -> hubKind() ? "running_hub" : "running";
        };
    }

    /** What a Furnace Engine can give right now: it needs a lit furnace beside or under it. */
    private float furnacePower() {
        float best = 0;
        for (Direction side : Direction.values()) {
            if (side == Direction.UP) continue;
            BlockState state = level.getBlockState(worldPosition.relative(side));
            if (!(state.getBlock() instanceof AbstractFurnaceBlock) || !state.getValue(AbstractFurnaceBlock.LIT)) continue;
            best = Math.max(best, state.is(Blocks.BLAST_FURNACE) ? BLAST_SU : FURNACE_SU);
        }
        status = best > 0 ? OK : NO_FURNACE;
        return best;
    }

    /** A HUB Engine has to touch its company's HUB, and the HUB only runs as many as the company has unlocked. */
    private boolean hubAllows() {
        for (Direction side : Direction.values()) {
            BlockPos at = worldPosition.relative(side);
            if (!(level.getBlockState(at).getBlock() instanceof HubBlock hub) || hub.gateway) continue;
            if (!(level.getBlockEntity(at) instanceof HubBlockEntity hubEntity)) continue;
            Company company = CompanyData.get(level.getServer()).byId(hubEntity.companyId);
            int allowed = company == null ? 0 : company.has("hub_upgrade_5") ? 2 : company.has("hub_upgrade_2") ? 1 : 0;
            int engines = 0;
            for (Direction around : Direction.values()) {
                if (level.getBlockState(at.relative(around)).getBlock() instanceof EngineBlock engine && engine.hub) engines++;
            }
            status = engines <= allowed ? OK : TOO_MANY;
            return engines <= allowed;
        }
        status = NO_HUB;
        return false;
    }

    @Override
    public float getGeneratedSpeed() {
        return capacity > 0 ? SPEED : 0;
    }

    @Override
    public float calculateAddedStressCapacity() {
        float perRpm = capacity / SPEED;
        lastCapacityProvided = perRpm;
        return perRpm;
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide()) return;
        if (burn > 0) burn--;
        if (capacity > 0 && (level.getGameTime() + worldPosition.hashCode()) % 20 == 0) com.meakaandre.siftec.tweak.Pollution.burning(level, worldPosition);
        if (++clock < 10 && !(hubKind() && burn == 0 && capacity > 0)) return;
        clock = 0;
        float target;
        if (hubKind()) {
            boolean allowed = hubAllows();
            if (allowed && burn == 0 && !fuel.getItem(0).isEmpty()) {
                burn = FuelUtil.burnDuration(level, fuel.getItem(0));
                fuel.removeItem(0, 1);
            }
            if (allowed && burn == 0) status = NO_FUEL;
            target = allowed && burn > 0 ? HUB_SU : 0;
        } else {
            target = furnacePower();
        }
        if (target != capacity) {
            capacity = target;
            updateGeneratedRotation();
            notifyUpdate();
        }
    }

    @Override
    protected void write(ValueOutput view, boolean clientPacket) {
        super.write(view, clientPacket);
        view.putInt("Burn", burn);
        view.putFloat("Capacity", capacity);
        view.store("Fuel", ItemStack.OPTIONAL_CODEC, fuel.getItem(0));
    }

    @Override
    protected void read(ValueInput view, boolean clientPacket) {
        super.read(view, clientPacket);
        burn = view.getIntOr("Burn", 0);
        capacity = view.getFloatOr("Capacity", 0);
        fuel.setItem(0, view.read("Fuel", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
    }
}
