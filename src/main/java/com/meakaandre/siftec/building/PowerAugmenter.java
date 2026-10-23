package com.meakaandre.siftec.building;

import com.meakaandre.siftec.power.Power;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** The Alien Power Augmenter in its grid: a flat capacity, and a boost to its grid's generators while it runs. */
final class PowerAugmenter extends FuseHolder {
    final ServerLevel level;
    final BuildingDef def;

    PowerAugmenter(ServerLevel level, ControllerBlockEntity c, BuildingDef def) {
        super(c);
        this.level = level;
        this.def = def;
    }

    @Override
    public double capacity() {
        return c.isRemoved() || c.tripped ? 0 : Power.su(def.mw());
    }

    @Override
    public double boost() {
        if (c.isRemoved() || c.tripped) return 0;
        if (c.fedTicks <= 0) {
            ItemStack in = c.input.getItem(0);
            if (BuildingPower.itemMj(PowerRole.AUGMENTER, in) > 0) {
                in.shrink(1);
                c.input.setChanged();
                c.fedTicks = BuildingPower.MATRIX_TICKS;
                c.made++;
            }
        }
        return c.fedTicks > 0 ? BuildingPower.AUGMENTER_FED_BOOST : BuildingPower.AUGMENTER_BOOST;
    }

    @Override
    public int boostTicks() {
        ItemStack in = c.input.getItem(0);
        return Math.max(0, c.fedTicks) + (BuildingPower.itemMj(PowerRole.AUGMENTER, in) > 0 ? in.getCount() * BuildingPower.MATRIX_TICKS : 0);
    }

    @Override
    public void spendBoost(int ticks) {
        int left = ticks;
        while (left > 0) {
            if (c.fedTicks <= 0) {
                ItemStack in = c.input.getItem(0);
                if (BuildingPower.itemMj(PowerRole.AUGMENTER, in) <= 0) break;
                in.shrink(1);
                c.input.setChanged();
                c.fedTicks = BuildingPower.MATRIX_TICKS;
                c.made++;
            }
            int n = Math.min(left, c.fedTicks);
            c.fedTicks -= n;
            left -= n;
        }
        c.setChanged();
    }

    @Override
    public void generated(double su) {
        if (c.fedTicks > 0) c.fedTicks--;
        c.lastGenerated = su;
        boolean on = su > 0;
        if (c.running != on) {
            c.running = on;
            BuildingPower.visual(level, c, on);
        }
        c.setChanged();
    }
}
