package com.meakaandre.siftec.node;

import com.meakaandre.siftec.company.Company;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.Locale;

/** Every kind of node, where it appears, and what comes out of it. */
public enum NodeType {
    //       weight, where,     what a miner makes ("" for fluids),  how far from spawn before it can appear
    IRON(30, Where.ANY, "minecraft:raw_iron", 0),
    COPPER(30, Where.ANY, "minecraft:raw_copper", 0),
    LIMESTONE(30, Where.ANY, "create:limestone", 0),
    COAL(20, Where.ANY, "minecraft:coal", 0),
    ZINC(10, Where.HILLS, "create:raw_zinc", 0),
    OIL(10, Where.OILY, "", 0),
    BAUXITE(6, Where.HOT, "siftec:raw_bauxite", 0),
    NITROGEN(6, Where.COLD, "", 0),
    SAM(2, Where.ANY, "siftec:sam", 3000),
    /** The Nether's only node. */
    QUARTZ(1, Where.NETHER, "minecraft:quartz", 0),
    /** Only inside sulfur caves, underground. */
    SULFUR(1, Where.SULFUR_CAVE, "cgs:sulfur", 0);

    public enum Where {ANY, HILLS, HOT, COLD, OILY, NETHER, SULFUR_CAVE}

    public final int weight;
    public final Where where;
    public final Identifier outputId;
    public final int minDistance;
    private Item output;

    NodeType(int weight, Where where, String output, int minDistance) {
        this.weight = weight;
        this.where = where;
        this.outputId = output.isEmpty() ? null : Identifier.parse(output);
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

    /** Oil and nitrogen come out as fluid, through a Pumpjack or a Resource Well Extractor. */
    public boolean isFluid() {
        return outputId == null;
    }

    /** The item a miner produces. Air for fluid nodes, or if the mod that owns the item is missing. */
    public Item output() {
        if (output == null) {
            output = outputId == null ? Items.AIR : BuiltInRegistries.ITEM.getOptional(outputId).orElse(Items.AIR);
        }
        return output;
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
