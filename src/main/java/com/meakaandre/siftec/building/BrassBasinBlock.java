package com.meakaandre.siftec.building;

import com.zurrtum.create.content.processing.basin.BasinBlock;

/**
 * The Brass Basin, the Manufacturer's and the Blender's: Create's Basin in Create's brass casing, run by Create's own
 * Basin block entity, that holds up to four stacks of each item (a plain Basin holds one), so recipes like the Heavy
 * Modular Frame's Core (119 Screws) fit. {@code mixin.building.BrassBasinInventoryMixin} gives it the room;
 * {@code mixin.building.BasinTypeMixin} lets Create's Basin block entity stand in it.
 */
public class BrassBasinBlock extends BasinBlock {
    /** Stacks of one item a Brass Basin holds. */
    public static final int STACKS = 4;

    public BrassBasinBlock(Properties properties) {
        super(properties);
    }
}
