package com.meakaandre.siftec.client.belt;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.belt.BeltLook;
import com.zurrtum.create.client.flywheel.lib.model.baked.PartialModel;

/** The one-block belt's own models: the side trim in each Mk's material, and the rounded end cap. */
public final class LowBeltPartials {
    public static final PartialModel RAIL_ANDESITE = of("rail_andesite"), RAIL_COPPER = of("rail_copper"), RAIL_BRASS = of("rail_brass"),
    /** Placeholder until SIFTEC steel's own texture is merged: the steel casing texture main already has. */
    RAIL_STEEL = of("rail_steel"), CAP = of("cap");

    private LowBeltPartials() {
    }

    private static PartialModel of(String name) {
        return PartialModel.of(Siftec.id("block/belt/" + name));
    }

    /** Declares the models before the game resolves its models (from the client initializer). */
    public static void init() {
    }

    public static PartialModel rail(BeltLook.Trim trim) {
        return switch (trim) {
            case ANDESITE -> RAIL_ANDESITE;
            case COPPER -> RAIL_COPPER;
            case BRASS -> RAIL_BRASS;
            case STEEL -> RAIL_STEEL;
        };
    }
}
