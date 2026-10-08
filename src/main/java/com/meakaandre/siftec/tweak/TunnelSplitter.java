package com.meakaandre.siftec.tweak;

import com.meakaandre.siftec.mixin.BeltInventoryAccessor;
import com.zurrtum.create.AllBlocks;
import com.zurrtum.create.api.behaviour.BlockEntityBehaviour;
import com.zurrtum.create.catnip.data.Iterate;
import com.zurrtum.create.content.kinetics.belt.BeltBlock;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.content.kinetics.belt.BeltHelper;
import com.zurrtum.create.content.kinetics.belt.BeltSlope;
import com.zurrtum.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.zurrtum.create.content.kinetics.belt.transport.BeltInventory;
import com.zurrtum.create.content.kinetics.belt.transport.BeltTunnelInteractionHandler;
import com.zurrtum.create.content.kinetics.belt.transport.TransportedItemStack;
import com.zurrtum.create.content.logistics.tunnel.BeltTunnelBlock;
import com.zurrtum.create.content.logistics.tunnel.BeltTunnelBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The pack's splitter. An Andesite Tunnel over a belt junction shares what passes through evenly between
 * going straight on and each side belt. A stack is divided as equally as it can be; what is left over, and
 * single items, go to the outputs in turn. (Create's own tunnel only peels one item off to each side.)
 */
public final class TunnelSplitter {
    /** Whose turn it is at each tunnel. One map per side: in single player the client's Ponder scenes run belts too, on their own thread. */
    private static final Map<BeltTunnelBlockEntity, Integer> TURN = new WeakHashMap<>(), CLIENT_TURN = new WeakHashMap<>();

    private TunnelSplitter() {
    }

    /** Null to let Create handle it; otherwise the value Create's method should return. */
    public static Boolean handle(BeltInventory inventory, TransportedItemStack current, float nextOffset) {
        BeltInventoryAccessor access = (BeltInventoryAccessor) inventory;
        BeltBlockEntity belt = access.siftec$belt();
        int currentSegment = (int) current.beltPosition;
        int upcomingSegment = (int) nextOffset;
        if (!access.siftec$positive() && nextOffset == 0) upcomingSegment = -1;
        if (currentSegment == upcomingSegment) return null;
        if (belt.getBlockState().getValue(BeltBlock.SLOPE) != BeltSlope.HORIZONTAL) return null;

        Level level = belt.getLevel();
        BeltTunnelBlockEntity tunnel = BeltTunnelInteractionHandler.getTunnelOnPosition(level, BeltHelper.getPositionForOffset(belt, upcomingSegment));
        if (tunnel == null) return null;
        BlockState state = tunnel.getBlockState();
        Direction moving = belt.getMovementFacing();
        if (!state.is(AllBlocks.ANDESITE_TUNNEL) || !BeltTunnelBlock.isJunction(state)
            || moving.getAxis() != state.getValue(BeltTunnelBlock.HORIZONTAL_AXIS)) {
            return null;
        }
        // the server decides; the client waits to be told
        if (level.isClientSide() && !belt.isVirtual()) return false;

        List<Direction> sides = new ArrayList<>(2);
        List<DirectBeltInputBehaviour> inputs = new ArrayList<>(2);
        for (Direction d : Iterate.horizontalDirections) {
            if (d.getAxis() == state.getValue(BeltTunnelBlock.HORIZONTAL_AXIS) || !tunnel.flaps.containsKey(d)) continue;
            BlockPos out = tunnel.getBlockPos().below().relative(d);
            if (!level.isLoaded(out)) return true;
            DirectBeltInputBehaviour input = BlockEntityBehaviour.get(level, out, DirectBeltInputBehaviour.TYPE);
            if (input == null || !input.canInsertFromSide(d)) continue;
            sides.add(d);
            inputs.add(input);
        }
        if (!sides.isEmpty()) {
            int outputs = sides.size() + 1;
            int count = current.stack.getCount();
            int share = count / outputs, extra = count % outputs;
            Map<BeltTunnelBlockEntity, Integer> turns = level.isClientSide() ? CLIENT_TURN : TURN;
            int turn = turns.getOrDefault(tunnel, 0);
            boolean movedAny = false;
            // outputs are numbered 0 = straight on, 1.. = the sides; the leftover items go to them in turn
            for (int i = 0; i < sides.size(); i++) {
                int slot = i + 1;
                int amount = share + (Math.floorMod(slot - turn, outputs) < extra ? 1 : 0);
                if (amount <= 0) continue;
                ItemStack rest = inputs.get(i).handleInsertion(current.stack.copyWithCount(amount), sides.get(i), false);
                int moved = amount - rest.getCount();
                if (moved <= 0) continue;
                current.stack.shrink(moved);
                movedAny = true;
                BeltTunnelInteractionHandler.flapTunnel(inventory, upcomingSegment, sides.get(i), false);
            }
            turns.put(tunnel, Math.floorMod(turn + extra, outputs));
            if (movedAny) belt.notifyUpdate();
        }
        boolean gone = current.stack.isEmpty();
        if (gone) current.stack = ItemStack.EMPTY;
        BeltTunnelInteractionHandler.flapTunnel(inventory, currentSegment, moving, false);
        if (!gone) BeltTunnelInteractionHandler.flapTunnel(inventory, upcomingSegment, moving.getOpposite(), true);
        return gone;
    }
}
