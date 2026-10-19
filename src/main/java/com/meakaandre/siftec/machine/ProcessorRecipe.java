package com.meakaandre.siftec.machine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One recipe of the Converter or the Particle Accelerator. Fluid amounts are in mB.
 * <p>
 * How the building's machines run it ({@link com.meakaandre.siftec.building.Processors}): {@code core} is the Core the
 * Mixer packs the inputs into; the Particle Accelerator's Crushing Wheels crush it ({@code crush}); in the Converter the
 * Deployer's {@code steps} each put one item on what lies on the Depot, the fan haunts {@code haunt}, and a fluid comes
 * out of {@code charged}, a Converter Cell the Item Drain empties. {@code mwMin} to {@code mwMax} is Satisfactory's power
 * over the recipe's cycle.
 */
public record ProcessorRecipe(List<Milestone.Cost> inputs, Identifier fluidIn, int fluidInMb, Identifier outItem, int outCount,
                              Identifier fluidOut, int fluidOutMb, int seconds, String alt, String name, Identifier core, Identifier crush,
                              Identifier haunt, List<Step> steps, Identifier charged, double mwMin, double mwMax, int batch) {
    /** A Deployer step: on the item {@code on} it puts one {@code apply}, which makes {@code makes}. */
    public record Step(Identifier on, Milestone.Cost apply, Identifier makes) {
    }

    private static final Map<String, List<ProcessorRecipe>> BY_MACHINE = new HashMap<>();

    public static List<ProcessorRecipe> of(String machine) {
        if (BY_MACHINE.isEmpty()) load();
        return BY_MACHINE.getOrDefault(machine, List.of());
    }

    private static void load() {
        JsonObject all = Milestones.raw().getAsJsonObject("processors");
        for (String machine : all.keySet()) {
            List<ProcessorRecipe> list = new ArrayList<>();
            for (JsonElement e : all.getAsJsonArray(machine)) {
                JsonObject o = e.getAsJsonObject();
                List<Milestone.Cost> inputs = new ArrayList<>();
                for (JsonElement i : o.getAsJsonArray("in")) {
                    inputs.add(new Milestone.Cost(i.getAsJsonObject().get("item").getAsString(), i.getAsJsonObject().get("count").getAsInt()));
                }
                JsonObject fin = o.has("fluid_in") ? o.getAsJsonObject("fluid_in") : null, fout = o.has("fluid_out") ? o.getAsJsonObject("fluid_out") : null;
                JsonObject out = o.has("out") ? o.getAsJsonObject("out") : null;
                list.add(new ProcessorRecipe(inputs,
                    fin == null ? null : Identifier.parse(fin.get("fluid").getAsString()), fin == null ? 0 : fin.get("mb").getAsInt(),
                    out == null ? null : Identifier.parse(out.get("item").getAsString()), out == null ? 0 : out.get("count").getAsInt(),
                    fout == null ? null : Identifier.parse(fout.get("fluid").getAsString()), fout == null ? 0 : fout.get("mb").getAsInt(),
                    o.get("seconds").getAsInt(), o.has("alt") ? o.get("alt").getAsString() : null, o.has("name") ? o.get("name").getAsString() : "",
                    id(o, "core"), id(o, "crush"), id(o, "haunt"), steps(o), id(o, "charged"),
                    o.has("mw") ? o.getAsJsonArray("mw").get(0).getAsDouble() : 0, o.has("mw") ? o.getAsJsonArray("mw").get(1).getAsDouble() : 0,
                    o.has("batch") ? Math.max(1, o.get("batch").getAsInt()) : 1));
            }
            BY_MACHINE.put(machine, list);
        }
    }

    private static Identifier id(JsonObject o, String key) {
        return o.has(key) ? Identifier.parse(o.get(key).getAsString()) : null;
    }

    private static List<Step> steps(JsonObject o) {
        List<Step> out = new ArrayList<>();
        if (!o.has("steps")) return out;
        for (JsonElement e : o.getAsJsonArray("steps")) {
            JsonObject st = e.getAsJsonObject();
            out.add(new Step(Identifier.parse(st.get("on").getAsString()), new Milestone.Cost(st.get("apply").getAsString(), 1),
                Identifier.parse(st.get("makes").getAsString())));
        }
        return List.copyOf(out);
    }

    /** An item by id, or air. */
    public static Item item(Identifier id) {
        return id == null ? Items.AIR : BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR);
    }

    /** Drops the table (the content is reloaded). */
    public static void forget() {
        BY_MACHINE.clear();
    }

    /**
     * Ticks one cycle really takes: a Core carries {@code batch} crafts (a recipe shorter than the Core's time is packed
     * two or three to a Core), so its Mixer takes that many recipe times to make it.
     */
    public long cycleTicks() {
        return Math.max(1L, (long) seconds * 20L * Math.max(1, batch));
    }

    /** What one Core (one cycle) gives of the main result. */
    public int outPerCycle() {
        return outCount * Math.max(1, batch);
    }

    public Item item() {
        return outItem == null ? Items.AIR : BuiltInRegistries.ITEM.getOptional(outItem).orElse(Items.AIR);
    }

    public static Fluid fluid(Identifier id) {
        return id == null ? Fluids.EMPTY : BuiltInRegistries.FLUID.getOptional(id).orElse(Fluids.EMPTY);
    }

    /** What the recipe makes, for messages. */
    public Component label() {
        if (outItem != null) return new ItemStack(item()).getItemName();
        return Component.translatable("siftec.processor.fluid", fluidOutMb, Component.translatable("fluid." + fluidOut.getNamespace() + "." + fluidOut.getPath()));
    }

    public boolean uses(ItemStack stack) {
        for (Milestone.Cost cost : inputs) if (cost.matches(stack)) return true;
        return false;
    }
}
