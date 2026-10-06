package com.meakaandre.siftec.block;

import com.meakaandre.siftec.node.Node;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.Optional;

public class MinerBlockEntity extends KineticBlockEntity {
    public final OutputContainer output = new OutputContainer(this::setChanged);
    private float progress;
    private Optional<Node> node = Optional.empty();
    private int recheck;
    /** Power Shards slotted in (0 to 3) and whether a Somersloop is. */
    public int shards;
    public boolean amplified;
    private static final float[] SHARD_SPEED = {1f, 1.5f, 2f, 2.5f}, SHARD_STRESS = {1f, 1.7f, 2.5f, 3.4f};

    /** Called after shards or a Somersloop go in or out: the stress this miner asks for has changed. */
    public void boostChanged() {
        setChanged();
        sendData();
        if (hasNetwork()) getOrCreateNetwork().updateStressFor(this, calculateStressApplied());
    }

    /** Throws out whatever is slotted in. */
    public void ejectBoosts() {
        if (level == null) return;
        if (shards > 0) net.minecraft.world.Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1, worldPosition.getZ() + 0.5,
            new ItemStack(com.meakaandre.siftec.registry.ModItems.PARTS.get("power_shard"), shards));
        if (amplified) net.minecraft.world.Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1, worldPosition.getZ() + 0.5,
            new ItemStack(com.meakaandre.siftec.registry.ModItems.PARTS.get("somersloop")));
        shards = 0;
        amplified = false;
        boostChanged();
    }

    public MinerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    private MinerTier tier() {
        return getBlockState().getBlock() instanceof MinerBlock miner ? miner.tier : MinerTier.MK1;
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
        if (speed == 0 || node.isEmpty() || Mining.isFull(output)) return;
        progress += speed * node.get().purity().multiplier * SHARD_SPEED[Math.min(shards, 3)];
        int cycle = tier().cycle;
        boolean made = false;
        while (progress >= cycle) {
            progress -= cycle;
            if (!Mining.produce(output, node.get())) {
                progress = 0;
                break;
            }
            if (amplified) Mining.produce(output, node.get());
            made = true;
        }
        if (made) setChanged();
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
        view.putInt("Shards", shards);
        view.putBoolean("Amplified", amplified);
        view.store("Output", ItemStack.OPTIONAL_CODEC, output.get());
    }

    @Override
    protected void read(ValueInput view, boolean clientPacket) {
        super.read(view, clientPacket);
        progress = view.getFloatOr("Progress", 0);
        shards = view.getIntOr("Shards", 0);
        amplified = view.getBooleanOr("Amplified", false);
        output.set(view.read("Output", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
    }
}
