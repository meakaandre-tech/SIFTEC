package com.meakaandre.siftec.prefab;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.BedBlock;

/**
 * A bunk in the HUB's living quarters. It is a real bed: sleeping in it sets the respawn point, and it is in the
 * minecraft:beds tag. Like the rest of the HUB it cannot be broken on its own.
 */
public class HubBedBlock extends BedBlock implements PrefabPart {
    public HubBedBlock(Properties properties) {
        super(DyeColor.ORANGE, properties);
    }
}
