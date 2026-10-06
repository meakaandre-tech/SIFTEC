package com.meakaandre.siftec.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps the designer's build volume marked with a frame of sparks, and known to the flight check. */
public class DesignerBlockEntity extends BlockEntity {
    /** Loaded designers: where each one's volume is, and the game time it last reported in. */
    private static final Map<ResourceKey<Level>, Map<BlockPos, Long>> LIVE = new ConcurrentHashMap<>();

    public DesignerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public int reach() {
        return getBlockState().getBlock() instanceof DesignerBlock block ? block.reach : 4;
    }

    /** The build volume: a cube sitting on top of the designer, centred on it. */
    public static AABB volume(BlockPos pos, int reach) {
        return new AABB(pos.getX() - reach, pos.getY() + 1, pos.getZ() - reach, pos.getX() + reach + 1, pos.getY() + 2 + 2 * reach, pos.getZ() + reach + 1);
    }

    public void serverTick() {
        if (!(level instanceof ServerLevel server) || (level.getGameTime() + worldPosition.hashCode()) % 40 != 0) return;
        LIVE.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>()).put(worldPosition.immutable(), level.getGameTime());
        AABB box = volume(worldPosition, reach());
        // the twelve edges of the volume
        for (double t = 0; t <= 1.0001; t += 1.0 / (2 * reach() + 1)) {
            double x = box.minX + box.getXsize() * t, y = box.minY + box.getYsize() * t, z = box.minZ + box.getZsize() * t;
            for (int i = 0; i < 4; i++) {
                double a = (i & 1) == 0 ? 0 : 1, b = (i & 2) == 0 ? 0 : 1;
                server.sendParticles(ParticleTypes.END_ROD, x, box.minY + box.getYsize() * a, box.minZ + box.getZsize() * b, 1, 0, 0, 0, 0);
                server.sendParticles(ParticleTypes.END_ROD, box.minX + box.getXsize() * a, y, box.minZ + box.getZsize() * b, 1, 0, 0, 0, 0);
                server.sendParticles(ParticleTypes.END_ROD, box.minX + box.getXsize() * a, box.minY + box.getYsize() * b, z, 1, 0, 0, 0, 0);
            }
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null) {
            Map<BlockPos, Long> map = LIVE.get(level.dimension());
            if (map != null) map.remove(worldPosition);
        }
    }

    /** True while the player is inside the volume of a loaded designer. Anyone may fly there. */
    public static boolean inside(ServerPlayer player) {
        Map<BlockPos, Long> map = LIVE.get(player.level().dimension());
        if (map == null) return false;
        long now = player.level().getGameTime();
        for (Map.Entry<BlockPos, Long> entry : map.entrySet()) {
            if (now - entry.getValue() > 200) continue;
            if (!(player.level().getBlockEntity(entry.getKey()) instanceof DesignerBlockEntity designer)) continue;
            if (volume(entry.getKey(), designer.reach()).inflate(0.5).contains(player.position())) return true;
        }
        return false;
    }

    public static void clear() {
        LIVE.clear();
    }
}
