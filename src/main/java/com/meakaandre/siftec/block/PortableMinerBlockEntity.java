package com.meakaandre.siftec.block;

import com.meakaandre.siftec.node.Node;
import com.zurrtum.create.foundation.blockEntity.SmartBlockEntity;
import com.zurrtum.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.List;
import java.util.Optional;

public class PortableMinerBlockEntity extends SmartBlockEntity {
    /** Ticks per item on a normal node: 20 items a minute. */
    private static final int TICKS_PER_ITEM = 60;

    public final OutputContainer output = new OutputContainer(this::setChanged);
    private float progress;
    private Optional<Node> node = Optional.empty();
    private int recheck;

    public PortableMinerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour<?>> behaviours) {
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide()) return;
        if (recheck-- <= 0) {
            recheck = 40;
            node = Mining.nodeUnder(level, worldPosition);
        }
        if (node.isEmpty() || Mining.isFull(output)) return;
        progress += node.get().purity().multiplier;
        if (progress >= TICKS_PER_ITEM) {
            progress -= TICKS_PER_ITEM;
            if (Mining.produce(output, node.get())) setChanged();
        }
    }

    public void giveTo(Player player) {
        Mining.give(output, player);
        setChanged();
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
