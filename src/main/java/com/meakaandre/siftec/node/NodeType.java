package com.meakaandre.siftec.node;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Locale;

/**
 * The ore nodes. Oil, nitrogen, quartz and sulfur are not in this list yet: they need their own extractors
 * and (for the last two) rules for the Nether and the sulfur caves.
 */
public enum NodeType {
    //       weight, where,            output item,                   distance from spawn before it can appear
    IRON(30, Where.ANY, "minecraft:raw_iron", 0),
    COPPER(30, Where.ANY, "minecraft:raw_copper", 0),
    LIMESTONE(30, Where.ANY, "create:limestone", 0),
    COAL(20, Where.ANY, "minecraft:coal", 0),
    ZINC(10, Where.HILLS, "create:raw_zinc", 0),
    BAUXITE(6, Where.HOT, "siftec:raw_bauxite", 0),
    SAM(2, Where.ANY, "siftec:sam", 3000);

    public enum Where {ANY, HILLS, HOT}

    public final int weight;
    public final Where where;
    public final Identifier outputId;
    public final int minDistance;
    private Item output;

    NodeType(int weight, Where where, String output, int minDistance) {
        this.weight = weight;
        this.where = where;
        this.outputId = Identifier.parse(output);
        this.minDistance = minDistance;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String key() {
        return "siftec.node." + id();
    }

    /** The item a miner produces. Air if the mod that owns it is missing. */
    public Item output() {
        if (output == null) {
            output = BuiltInRegistries.ITEM.getOptional(outputId).orElse(Items.AIR);
        }
        return output;
    }

    public static NodeType byId(String id) {
        for (NodeType type : values()) {
            if (type.id().equals(id)) return type;
        }
        return null;
    }
}
