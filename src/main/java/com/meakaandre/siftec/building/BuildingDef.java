package com.meakaandre.siftec.building;

import com.meakaandre.siftec.hub.Milestone;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * One building the Build Gun can place: its tab, its build cost (Satisfactory parts, taken from the inventory), the
 * milestone or MAM research that unlocks it ("" for always), and its structure template,
 * {@code data/siftec/structure/building/<id>.nbt}. Dev buildings (the test buildings) only show in creative.
 * A {@code node} building (a miner, an extractor) has to stand over a resource node whose clump is mined away; its
 * template leaves the node's cell ({@code nodeCell}, in the Build Gun frame) out, so the node stays where it is.
 * <p>
 * {@code logic} says how it works ({@link BuildingLogic}: "basin", "smelter", "miner", "portable_miner", "storage", or
 * "" for none), {@code su} the SU it draws (its New Age motors give exactly that). A miner really mines its node:
 * {@code breakTicks} is how long its drill takes to break the node once it has regrown (its Mk's speed; the node's
 * purity sets the regrowth) and {@code fortune} its built-in Fortune (Mk.2 III, Mk.3 V).
 * <p>
 * Power ({@link BuildingPower}): {@code power} is its part in the grid ("" for a plain consumer or nothing, "pole",
 * "storage", "augmenter", or a generator: "biomass", "coal", "fuel", "geothermal", "nuclear"), {@code mw} the
 * megawatts a generator gives, {@code water} the m3 of water a minute it takes at full load, {@code lines} how many
 * wires its connector takes, and {@code vent} the open cell a Geothermal Generator must stand over a geyser with.
 * <p>
 * {@code prefab} names a structure the Build Gun puts down through {@link com.meakaandre.siftec.prefab.Prefabs} instead
 * of a structure template ("hub": the HUB), or is "" for an ordinary building.
 */
public record BuildingDef(String id, Category category, List<Milestone.Cost> cost, String unlock, boolean dev, boolean node,
                          int @Nullable [] nodeCell, String logic, int su, int breakTicks, int fortune, String power, double mw, double water, int lines,
                          int @Nullable [] vent, String prefab) {
    public BuildingDef(String id, Category category, List<Milestone.Cost> cost, String unlock, boolean dev, boolean node) {
        this(id, category, cost, unlock, dev, node, node ? new int[]{0, 0, 1} : null, "", 0, 0, 0, "", 0, 0, 0, null, "");
    }

    /** The HUB: put down by the Build Gun through {@link com.meakaandre.siftec.prefab.Prefabs}. */
    public boolean hub() {
        return prefab.equals("hub");
    }

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

    // records compare arrays by identity: two definitions are the same building when their ids are
    @Override
    public boolean equals(Object o) {
        return o instanceof BuildingDef other && other.id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
