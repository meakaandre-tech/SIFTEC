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

/** One recipe of the Converter or the Particle Accelerator. Fluid amounts are in mB. */
public record ProcessorRecipe(List<Milestone.Cost> inputs, Identifier fluidIn, int fluidInMb, Identifier outItem, int outCount,
                              Identifier fluidOut, int fluidOutMb, int seconds) {
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
                    o.get("seconds").getAsInt()));
            }
            BY_MACHINE.put(machine, list);
        }
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
