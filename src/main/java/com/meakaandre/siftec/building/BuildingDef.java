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
 * "" for none), {@code su} the SU it draws (its motor gives exactly that), and {@code rate} a miner's items a minute
 * on a normal node.
 */
public record BuildingDef(String id, Category category, List<Milestone.Cost> cost, String unlock, boolean dev, boolean node,
                          int @Nullable [] nodeCell, String logic, int su, int rate) {
    public BuildingDef(String id, Category category, List<Milestone.Cost> cost, String unlock, boolean dev, boolean node) {
        this(id, category, cost, unlock, dev, node, node ? new int[]{0, 0, 1} : null, "", 0, 0);
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
