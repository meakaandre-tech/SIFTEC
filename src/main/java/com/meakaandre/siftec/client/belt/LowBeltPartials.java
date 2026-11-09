package com.meakaandre.siftec.client.belt;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.belt.BeltLook;
import com.zurrtum.create.client.flywheel.lib.model.baked.PartialModel;
import com.zurrtum.create.content.kinetics.belt.BeltPart;

import java.util.EnumMap;
import java.util.Map;

/**
 * The one-block belt's own models: the side trim in each Mk's material and the rounded end cap, for flat and vertical
 * belts; and for sloped belts the trim of Create's three diagonal pieces (middle, start, end) and the caps of its ends,
 * made in the model space of Create's own diagonal pieces.
 */
public final class LowBeltPartials {
    public static final PartialModel RAIL_ANDESITE = of("rail_andesite"), RAIL_COPPER = of("rail_copper"), RAIL_BRASS = of("rail_brass"),
    /** Mk.6: the Steel Casing's own side. */
    RAIL_STEEL = of("rail_steel"), CAP = of("cap"), SLOPE_CAP_START = of("slope_cap_start"), SLOPE_CAP_END = of("slope_cap_end");
    private static final Map<BeltLook.Trim, PartialModel[]> SLOPES = new EnumMap<>(BeltLook.Trim.class);

    static {
        for (BeltLook.Trim trim : BeltLook.Trim.values()) {
            String m = trim.name().toLowerCase(java.util.Locale.ROOT);
            SLOPES.put(trim, new PartialModel[]{of("slope_" + m), of("slope_start_" + m), of("slope_end_" + m)});
        }
    }

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

    /** A sloped belt block's trim: Create's diagonal piece for this part of the belt is the middle, start or end one. */
    public static PartialModel slope(BeltLook.Trim trim, BeltPart part) {
        PartialModel[] m = SLOPES.get(trim);
        return switch (part) {
            case START -> m[1];
            case END -> m[2];
            default -> m[0];
        };
    }

    /** A sloped belt's end cap, at the face its start or end piece wraps round. */
    public static PartialModel slopeCap(BeltPart part) {
        return part == BeltPart.START ? SLOPE_CAP_START : SLOPE_CAP_END;
    }
}
