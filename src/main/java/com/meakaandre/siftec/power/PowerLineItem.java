package com.meakaandre.siftec.power;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Power Line: click one pole, then another in range, to join them. Each join uses one line. A Deployer (or any
 * machine's fake player) holding lines joins the pole it uses them on to the nearest pole in range that is not
 * joined to it yet, so poles can be strung by machines too.
 */
public class PowerLineItem extends Item {
    private static final Map<UUID, BlockPos> FIRST = new ConcurrentHashMap<>();

    /** Forgets every half-made line; called when the server stops. */
    public static void forget() {
        FIRST.clear();
    }

    public PowerLineItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!(level.getBlockEntity(pos) instanceof PoleBlockEntity pole)) return InteractionResult.PASS;
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.SUCCESS;
        if (com.meakaandre.siftec.company.Companies.isFake(player)) {
            if (linkNearest(level, pos, pole, player)) context.getItemInHand().shrink(1);
            return InteractionResult.SUCCESS;
        }
        BlockPos first = FIRST.remove(player.getUUID());
        if (first == null || first.equals(pos) || !(level.getBlockEntity(first) instanceof PoleBlockEntity other)) {
            FIRST.put(player.getUUID(), pos);
            player.sendOverlayMessage(Component.translatable("siftec.line.first"));
            return InteractionResult.SUCCESS;
        }
        int range = Math.min(range(level, pos), range(level, first));
        if (Math.sqrt(first.distSqr(pos)) > range) {
            player.sendOverlayMessage(Component.translatable("siftec.line.too_far", range));
            return InteractionResult.SUCCESS;
        }
        if (pole.lines.size() >= PoleBlockEntity.MAX_LINES || other.lines.size() >= PoleBlockEntity.MAX_LINES) {
            player.sendOverlayMessage(Component.translatable("siftec.line.full", PoleBlockEntity.MAX_LINES));
            return InteractionResult.SUCCESS;
        }
        if (pole.link(first) | other.link(pos)) {
            if (!player.hasInfiniteMaterials()) context.getItemInHand().shrink(1);
            player.sendOverlayMessage(Component.translatable("siftec.line.joined"));
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Joins the pole to the nearest loaded pole in range that it is not joined to yet and that has a free line,
     * where the machine's owner may build (its own claim or unclaimed land). True if a line was made.
     */
    public static boolean linkNearest(Level level, BlockPos pos, PoleBlockEntity pole, net.minecraft.world.entity.player.Player user) {
        if (pole.lines.size() >= PoleBlockEntity.MAX_LINES || !com.meakaandre.siftec.claim.Claims.allowed(user, level, pos)) return false;
        int reach = range(level, pos);
        PoleBlockEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int cx = (pos.getX() - reach) >> 4; cx <= (pos.getX() + reach) >> 4; cx++) {
            for (int cz = (pos.getZ() - reach) >> 4; cz <= (pos.getZ() + reach) >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                for (net.minecraft.world.level.block.entity.BlockEntity be : level.getChunk(cx, cz).getBlockEntities().values()) {
                    if (!(be instanceof PoleBlockEntity other) || other == pole || other.isRemoved()) continue;
                    BlockPos at = other.getBlockPos();
                    if (pole.lines.contains(at.subtract(pos)) || other.lines.size() >= PoleBlockEntity.MAX_LINES) continue;
                    double distance = Math.sqrt(at.distSqr(pos));
                    if (distance > Math.min(reach, range(level, at)) || distance >= bestDistance) continue;
                    if (!com.meakaandre.siftec.claim.Claims.allowed(user, level, at)) continue;
                    best = other;
                    bestDistance = distance;
                }
            }
        }
        if (best == null) return false;
        return pole.link(best.getBlockPos()) | best.link(pos);
    }

    private static int range(Level level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof PoleBlock pole ? pole.range : 0;
    }
}
