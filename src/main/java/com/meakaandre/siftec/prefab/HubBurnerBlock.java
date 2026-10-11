package com.meakaandre.siftec.prefab;

import com.meakaandre.siftec.engine.EngineBlock;

/**
 * One of the HUB's two built-in burners (Satisfactory's HUB Biomass Burners), as SIFTEC's HUB Engine: it burns
 * fuel put into it and turns a shaft set on top. HUB Upgrade 2 adds the first, HUB Upgrade 5 the second.
 */
public class HubBurnerBlock extends EngineBlock implements PrefabPart {
    public HubBurnerBlock(Properties properties) {
        super(true, properties);
    }
}
