package com.meakaandre.siftec.building;

import com.meakaandre.siftec.hub.Milestone;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * One building the Build Gun can place: its tab, its build cost (Satisfactory parts, taken from the inventory), the
 * milestone or MAM research that unlocks it ("" for always), and its structure template,
 * {@code data/siftec/structure/building/<id>.nbt}. Dev buildings (the test buildings) only show in creative.
 * A {@code node} building (a miner, an extractor) has to stand over a resource node whose clump is mined away; its
 * template leaves the node's cell out (a structure void), so the node stays where it is.
 */
public record BuildingDef(String id, Category category, List<Milestone.Cost> cost, String unlock, boolean dev, boolean node) {
    public String nameKey() {
        return "siftec.building." + id;
    }

    public Component name() {
        return Component.translatable(nameKey());
    }

    /** Where the template is, inside the mod jar (and so for structure blocks as {@code siftec:building/<id>}). */
    public String templatePath() {
        return "/data/siftec/structure/building/" + id + ".nbt";
    }
}
