package com.meakaandre.siftec.prefab;

import com.meakaandre.siftec.engine.EngineBlock;

/**
 * One of the HUB's two built-in Biomass Burners (Satisfactory's HUB Biomass Burners): it burns leaves, logs, Biomass
 * and Solid Biofuel put into it (by hand, hopper, funnel or chute) for Satisfactory's 30 MW, 6,144 SU, and turns a
 * shaft set on top. HUB Upgrade 2 adds the first, HUB Upgrade 5 the second.
 */
public class HubBurnerBlock extends EngineBlock implements PrefabPart {
    public HubBurnerBlock(Properties properties) {
        super(true, properties);
    }
}
