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
        progress += speed * node.get().purity().multiplier;
        int cycle = tier().cycle;
        boolean made = false;
        while (progress >= cycle) {
            progress -= cycle;
            if (!Mining.produce(output, node.get())) {
                progress = 0;
                break;
            }
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
        lastStressApplied = tier().stress;
        return lastStressApplied;
    }

    @Override
    public void destroy() {
        super.destroy();
        if (level != null) Containers.dropContents(level, worldPosition, output);
    }

    @Override
    protected void write(ValueOutput view, boolean clientPacket) {
        super.write(view, clientPacket);
        view.putFloat("Progress", progress);
        view.store("Output", ItemStack.OPTIONAL_CODEC, output.get());
    }

    @Override
    protected void read(ValueInput view, boolean clientPacket) {
        super.read(view, clientPacket);
        progress = view.getFloatOr("Progress", 0);
        output.set(view.read("Output", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
    }
}
