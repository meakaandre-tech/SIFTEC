package com.meakaandre.siftec.owner;

import com.meakaandre.siftec.company.Company;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Upgrades a company has earned that change how its machines behave. */
public final class Upgrades {
    private Upgrades() {
    }

    /** Pipeline Engineering Mk.2: the company's Mechanical Pumps reach twice as far and push twice as hard. */
    public static int pumps(BlockEntity pump) {
        // asked by every pump every tick: the company is looked up again at most every five seconds
        if (!(pump.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return 1;
        long now = level.getGameTime();
        long[] known = PUMPS.get(pump);
        if (known != null && now - known[0] < 100 && now >= known[0]) return (int) known[1];
        Company company = Ownership.of(pump);
        int factor = company != null && company.hasToken("pumps:2") ? 2 : 1;
        PUMPS.put(pump, new long[]{now, factor});
        return factor;
    }

    /** Pump -> {when looked up, factor}. Server thread only; forgets pumps that are gone. */
    private static final java.util.Map<BlockEntity, long[]> PUMPS = new java.util.WeakHashMap<>();
}
