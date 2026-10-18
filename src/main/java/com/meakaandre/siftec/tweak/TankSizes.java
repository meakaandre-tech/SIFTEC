package com.meakaandre.siftec.tweak;

import com.meakaandre.siftec.building.BuildingIndex;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.owner.Ownership;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Create's Fluid Tanks stay one block wide (as tall as Create allows) until the company that placed them has done
 * Petroleum Power, which unlocks the larger tanks up to Create's 3 by 3 ("tanks:large"). Tanks inside a Build Gun
 * building or the HUB are the building's own and keep their shape; a tank nobody owns (a structure, a command) is not
 * held back.
 */
public final class TankSizes {
    /** The token Petroleum Power carries. */
    public static final String TOKEN = "tanks:large";

    private TankSizes() {
    }

    /** True if this tank may only be one block wide. */
    public static boolean small(BlockEntity tank) {
        if (!(tank.getLevel() instanceof ServerLevel level)) return false;
        if (BuildingIndex.isMember(level, tank.getBlockPos())) return false;
        Company company = Ownership.of(tank);
        return company != null && !company.hasToken(TOKEN);
    }
}
