package com.meakaandre.siftec.building;

import com.meakaandre.siftec.mixin.building.BasinOperatorAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * Keeps a building's Press or Mixer to Satisfactory's time per recipe. Create runs a Basin recipe as fast as the
 * machine turns; inside a building each start waits until the last recipe's Satisfactory time is up, so a
 * Constructor making Iron Plate starts one every 6 seconds whatever its speed. Machines outside a building, the
 * HUB's hand-cranked Craft Station included, run as Create has them.
 */
public final class BasinPace {
    private BasinPace() {
    }

    static @Nullable ControllerBlockEntity controllerOf(BlockEntity machine) {
        if (!(machine.getLevel() instanceof ServerLevel level)) return null;
        BlockPos c = BuildingIndex.controllerOf(level, machine.getBlockPos());
        return c != null && level.getBlockEntity(c) instanceof ControllerBlockEntity controller ? controller : null;
    }

    /** True while the machine's building is still waiting out its last recipe. */
    public static boolean blocked(BlockEntity machine) {
        ControllerBlockEntity c = controllerOf(machine);
        return c != null && c.getLevel() != null && c.getLevel().getGameTime() < c.nextCraft;
    }

    /** The machine has just started a recipe: the next may start once this one's Satisfactory time is up. */
    public static void started(BlockEntity machine) {
        ControllerBlockEntity c = controllerOf(machine);
        if (c == null || !(c.getLevel() instanceof ServerLevel level)) return;
        Recipe<?> recipe = ((BasinOperatorAccessor) machine).siftec$recipe();
        int ticks = recipe == null ? 0 : MachineTimes.ticks(level.getServer(), recipe);
        c.nextCraft = level.getGameTime() + ticks;
        c.made++;
        c.setChanged();
    }
}
