package com.meakaandre.siftec.owner;

import com.meakaandre.siftec.company.Company;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Upgrades a company has earned that change how its machines behave. */
public final class Upgrades {
    private Upgrades() {
    }

    /** Pipeline Engineering Mk.2: the company's Mechanical Pumps reach twice as far and push twice as hard. */
    public static int pumps(BlockEntity pump) {
        Company company = Ownership.of(pump);
        return company != null && company.hasToken("pumps:2") ? 2 : 1;
    }
}
