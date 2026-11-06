package com.meakaandre.siftec.client;

import com.meakaandre.siftec.tweak.ShaftlessBelts;
import com.zurrtum.create.AllDataComponents;
import com.zurrtum.create.AllItems;
import com.zurrtum.create.content.kinetics.belt.item.BeltConnectorItem;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Create's belt preview, a line of dust from the first end to the spot under the cursor (green when the belt can go,
 * red when it cannot), for belts laid with no shafts. A first end and a cursor both on shafts are left to Create's own
 * preview, which this mirrors.
 */
public final class ShaftlessBeltPreview {
    private static final int CONNECT_COLOR = ARGB.colorFromFloat(1, 0.3f, 0.9f, 0.5f);
    private static final int NO_CONNECT_COLOR = ARGB.colorFromFloat(1, 0.9f, 0.3f, 0.5f);

    private ShaftlessBeltPreview() {
    }

    /** One client tick (from the mixin on Create's preview): true if it drew the shaftless preview, so Create's is skipped. */
    public static boolean tick(Minecraft mc) {
        Player player = mc.player;
        Level level = mc.level;
        if (player == null || level == null || mc.gui.screen() != null) return false;
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = player.getItemInHand(hand);
            if (!held.is(AllItems.BELT_CONNECTOR) || !held.has(AllDataComponents.BELT_FIRST_SHAFT)) continue;
            BlockPos first = held.get(AllDataComponents.BELT_FIRST_SHAFT);
            RandomSource random = level.getRandom();
            HitResult hit = mc.hitResult;
            if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
                if (ShaftlessBelts.isShaft(level, first)) return false;
                if (random.nextInt(50) == 0) dust(level, CONNECT_COLOR, Vec3.atCenterOf(first).add(offset(random), offset(random), offset(random)));
                return true;
            }
            BlockPos selected = ShaftlessBelts.target(level, block.getBlockPos(), block.getDirection());
            if (ShaftlessBelts.isShaft(level, first) && ShaftlessBelts.isShaft(level, selected)) return false;
            if (!selected.closerThan(first, BeltConnectorItem.maxLength())) return true;
            Axis axis = ShaftlessBelts.axis(level, first, selected, player.getDirection());
            boolean ok = axis != null && ShaftlessBelts.canConnect(level, first, selected, axis, player);
            line(level, random, first, selected, axis, ok ? CONNECT_COLOR : NO_CONNECT_COLOR);
            return true;
        }
        return false;
    }

    /** The dust line, drawn as Create draws it: along the nearest way a belt could run from the first end. */
    private static void line(Level level, RandomSource random, BlockPos first, BlockPos selected, @Nullable Axis known, int color) {
        Vec3 start = Vec3.atLowerCornerOf(first);
        Vec3 actual = Vec3.atLowerCornerOf(selected).subtract(start);
        Axis axis = known != null ? known : Math.abs(actual.x) >= Math.abs(actual.z) ? Axis.Z : Axis.X;
        Vec3 diff = new Vec3(axis == Axis.X ? 0 : actual.x, axis == Axis.Y ? 0 : actual.y, axis == Axis.Z ? 0 : actual.z);
        double x = Math.abs(diff.x), y = Math.abs(diff.y), z = Math.abs(diff.z);
        float length = (float) Math.max(x, Math.max(y, z));
        Vec3 step = diff.normalize();
        int sames = (x == y ? 1 : 0) + (y == z ? 1 : 0) + (z == x ? 1 : 0);
        if (sames == 0) {
            List<Vec3> valid = new ArrayList<>();
            for (int i = -1; i <= 1; i++)
                for (int j = -1; j <= 1; j++)
                    for (int k = -1; k <= 1; k++) {
                        if (axis.choose(i, j, k) != 0 || axis == Axis.Y && i != 0 && k != 0 || i == 0 && j == 0 && k == 0) continue;
                        valid.add(new Vec3(i, j, k));
                    }
            Vec3 best = valid.getFirst();
            for (Vec3 v : valid) if (step.distanceTo(v) < step.distanceTo(best)) best = v;
            step = best;
        }
        if (axis == Axis.Y && step.x != 0 && step.z != 0) return;
        step = new Vec3(Math.signum(step.x), Math.signum(step.y), Math.signum(step.z));
        for (float f = 0; f < length; f += 0.0625f) {
            if (random.nextInt(10) == 0) dust(level, color, start.add(step.scale(f)).add(0.5, 0.5, 0.5));
        }
    }

    private static void dust(Level level, int color, Vec3 at) {
        level.addParticle(new DustParticleOptions(color, 1), at.x, at.y, at.z, 0, 0, 0);
    }

    private static float offset(RandomSource random) {
        return (random.nextFloat() - 0.5f) * 0.5f;
    }
}
