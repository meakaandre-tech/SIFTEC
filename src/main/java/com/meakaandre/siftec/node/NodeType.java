package com.meakaandre.siftec.node;

import com.meakaandre.siftec.company.Company;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Locale;

/**
 * Every kind of node, where it appears and how common it is. What a node looks like and what comes out of it is in
 * {@link NodeLook}: one look per type, except stone nodes, which are each one natural stone ({@link StoneKind}).
 */
public enum NodeType {
    //        weight, where,          a fluid?, how far from spawn before it can appear
    IRON(30, Where.ANY, false, 0),
    COPPER(30, Where.ANY, false, 0),
    /** One natural stone each (granite, diorite, tuff, ...): what the pack's recipes take where Satisfactory takes Concrete. */
    STONE(30, Where.ANY, false, 0),
    COAL(20, Where.ANY, false, 0),
    ZINC(10, Where.HILLS, false, 0),
    OIL(10, Where.OILY, true, 0),
    BAUXITE(6, Where.HOT, false, 0),
    NITROGEN(6, Where.COLD, true, 0),
    URANIUM(4, Where.ANY, false, 2000),
    SAM(2, Where.ANY, false, 3000),
    /** The Nether's only node. */
    QUARTZ(1, Where.NETHER, false, 0),
    /** Only inside sulfur caves, underground. */
    SULFUR(1, Where.SULFUR_CAVE, false, 0);

    public enum Where {ANY, HILLS, HOT, COLD, OILY, NETHER, SULFUR_CAVE}

    public final int weight;
    public final Where where;
    private final boolean fluid;
    public final int minDistance;

    NodeType(int weight, Where where, boolean fluid, int minDistance) {
        this.weight = weight;
        this.where = where;
        this.fluid = fluid;
        this.minDistance = minDistance;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String key() {
        return "siftec.node." + id();
    }

    public ResourceKey<Level> dimension() {
        return where == Where.NETHER ? Level.NETHER : Level.OVERWORLD;
    }

    /** Oil and nitrogen come out as fluid, through a Pumpjack or a Resource Well Extractor: their node is never mined. */
    public boolean isFluid() {
        return fluid;
    }

    /** Iron is on the scanner from the start; the others are added by milestones. */
    public boolean onScanner(Company company) {
        return this == IRON || company == null || company.hasToken("scanner:" + id());
    }

    public static NodeType byId(String id) {
        for (NodeType type : values()) {
            if (type.id().equals(id)) return type;
        }
        return null;
    }
}
