package com.meakaandre.siftec.block;

import com.meakaandre.siftec.node.Node;
import com.zurrtum.create.api.behaviour.BlockEntityBehaviour;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import com.zurrtum.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.zurrtum.create.infrastructure.fluids.FluidStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * A powered miner, worked like the old node drills: the shaft's RPM (at most 256) is added to the progress every
 * tick, and every {@link MinerTier#CYCLE} of progress brings up purity x the mark's output (fractions carry over)
 * and uses {@link MinerTier#FLUID_PER_CYCLE} mB of the mark's fluid. Power Shards speed it up and a Somersloop
 * doubles what each cycle brings up, both at more stress. The output slot is open to funnels, chutes and belts.
 */
public class MinerBlockEntity extends KineticBlockEntity {
    /** Fluid is counted in droplets: 81 to the millibucket. */
    public static final int MB = 81;

    public final OutputContainer output = new OutputContainer(this::setChanged);
    public SmartFluidTankBehaviour tank;
    private float progress;
    /** Fractions of an item a cycle brought up (an impure node gives half a cycle's worth), kept for the next one. */
    private float pending;
    private Optional<Node> node = Optional.empty();
    private int recheck;
    private String status = "starting";
    /** Power Shards slotted in (0 to 3) and whether a Somersloop is. */
    public int shards;
    public boolean amplified;
    /** Cycles finished since load, for the selftest. */
    public int cycles;
    private static final float[] SHARD_SPEED = {1f, 1.5f, 2f, 2.5f}, SHARD_STRESS = {1f, 1.7f, 2.5f, 3.4f};

    public MinerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour<?>> behaviours) {
        super.addBehaviours(behaviours);
        // takes only this mark's fluid, and nothing can pump it back out
        tank = SmartFluidTankBehaviour.single(this, MinerTier.TANK * MB, (behaviour, variety, max) -> new SmartFluidTankBehaviour.InternalFluidHandler(behaviour, variety, max) {
            @Override
            public boolean canInsert(int slot, FluidStack stack, @Nullable Direction dir) {
                return super.canInsert(slot, stack, dir) && stack.getFluid().isSame(tier().fluid());
            }
        }).forbidExtraction();
        behaviours.add(tank);
    }

    /** Called after shards or a Somersloop go in or out: the stress this miner asks for has changed. */
    public void boostChanged() {
        setChanged();
        sendData();
        if (hasNetwork()) getOrCreateNetwork().updateStressFor(this, calculateStressApplied());
    }

    /** Throws out whatever is slotted in. */
    public void ejectBoosts() {
        if (level == null) return;
        if (shards > 0) Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1, worldPosition.getZ() + 0.5,
            new ItemStack(com.meakaandre.siftec.registry.ModItems.PARTS.get("power_shard"), shards));
        if (amplified) Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1, worldPosition.getZ() + 0.5,
            new ItemStack(com.meakaandre.siftec.registry.ModItems.PARTS.get("somersloop")));
        shards = 0;
        amplified = false;
        boostChanged();
    }

    public MinerTier tier() {
        return getBlockState().getBlock() instanceof MinerBlock miner ? miner.tier : MinerTier.MK1;
    }

    /** mB of fluid in the tank. */
    public int fluidAmount() {
        return tank == null ? 0 : tank.getPrimaryHandler().getFluid().getAmount() / MB;
    }

    /** Puts fluid in as a pipe would (only this mark's fluid goes in). Returns the mB taken. */
    public int fill(net.minecraft.world.level.material.Fluid fluid, int mb) {
        return tank.getCapability().insert(new FluidStack(fluid, mb * MB)) / MB;
    }

    /** Items one cycle brings up on this miner's node: purity x mark, doubled by a Somersloop. 0 off a node. */
    public float perCycle() {
        return node.map(n -> n.purity().multiplier * tier().output * (amplified ? 2 : 1)).orElse(0f);
    }

    public String status() {
        return status;
    }

    public float progress() {
        return progress;
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide()) return;
        if (recheck-- <= 0) {
            recheck = 40;
            node = Mining.nodeUnder(level, worldPosition);
        }
        float speed = Math.abs(getSpeed());
        if (node.isEmpty()) {
            setStatus("not on a node");
            return;
        }
        Item item = node.get().type().output();
        if (item == Items.AIR) {
            setStatus("nothing to mine here");
            return;
        }
        if (speed == 0 || isOverStressed()) {
            setStatus(speed == 0 ? "needs rotation from above" : "overstressed");
            return;
        }
        if (fluidAmount() < MinerTier.FLUID_PER_CYCLE) {
            setStatus("needs " + tier().fluidName);
            return;
        }
        progress += Math.min(speed, 256f) * SHARD_SPEED[Math.min(shards, 3)];
        if (progress < MinerTier.CYCLE) {
            setStatus("drilling");
            return;
        }
        float amount = pending + perCycle();
        int whole = (int) amount;
        ItemStack stack = output.get();
        if (whole > 0 && !stack.isEmpty() && (!stack.is(item) || stack.getCount() + whole > stack.getMaxStackSize())) {
            progress = MinerTier.CYCLE;
            setStatus("output full");
            return;
        }
        if (whole > 0) {
            if (stack.isEmpty()) output.set(new ItemStack(item, whole));
            else stack.grow(whole);
        }
        pending = amount - whole;
        progress -= MinerTier.CYCLE;
        tank.getPrimaryHandler().extract(new FluidStack(tier().fluid(), MinerTier.FLUID_PER_CYCLE * MB));
        cycles++;
        setStatus("drilling");
        setChanged();
    }

    private void setStatus(String s) {
        if (!s.equals(status)) {
            status = s;
            sendData();
        }
    }

    /** What the miner tells a player who clicks it. */
    public Component describe() {
        return Component.literal(getBlockState().getBlock().getName().getString() + ": " + status + " | " + tier().fluidName + " "
            + fluidAmount() + " / " + MinerTier.TANK + " mB | " + perCycle() + " per cycle");
    }

    public void giveTo(Player player) {
        Mining.give(output, player);
        setChanged();
    }

    @Override
    public float calculateStressApplied() {
        lastStressApplied = tier().stress * SHARD_STRESS[Math.min(shards, 3)] * (amplified ? 4f : 1f);
        return lastStressApplied;
    }

    @Override
    public void destroy() {
        super.destroy();
        if (level != null) {
            Containers.dropContents(level, worldPosition, output);
            ejectBoosts();
        }
    }

    @Override
    protected void write(ValueOutput view, boolean clientPacket) {
        super.write(view, clientPacket);
        view.putFloat("Progress", progress);
        view.putFloat("Pending", pending);
        view.putInt("Shards", shards);
        view.putBoolean("Amplified", amplified);
        view.putString("Status", status);
        view.store("Output", ItemStack.OPTIONAL_CODEC, output.get());
    }

    @Override
    protected void read(ValueInput view, boolean clientPacket) {
        super.read(view, clientPacket);
        progress = view.getFloatOr("Progress", 0);
        pending = view.getFloatOr("Pending", 0);
        shards = view.getIntOr("Shards", 0);
        amplified = view.getBooleanOr("Amplified", false);
        status = view.getStringOr("Status", "");
        output.set(view.read("Output", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
    }
}
