package com.meakaandre.siftec.command;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.blueprint.DesignerBlock;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.BuildingPower;
import com.meakaandre.siftec.building.BuildingTests;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.Category;
import com.meakaandre.siftec.building.MachineTimes;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.Alternates;
import com.meakaandre.siftec.hub.Locks;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.owner.RecipeLocks;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.sink.Sink;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.content.processing.sequenced.SequencedAssemblyRecipe;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /siftec selftest datafix}: the review's recipe and data fixes, on a test site of its own (X 10800, Z -10800).
 * Every check prints "SELFTEST datafix"; failures say FAIL; the last line counts them. Most read the loaded recipes,
 * locks and tables; tools/check_recipes.py checks the same data before it is written.
 * <ul>
 * <li>4: a Hard Drive offers an alternate only once the company has the building that runs it;</li>
 * <li>5: Create's ingot pressing is gone, Diesel Generators' biodiesel needs Fluid Packaging, and every step of another
 * mod's sequenced assembly is locked as its final result is;</li>
 * <li>11: a Uranium Fuel Rod leaves 50 Uranium Waste, a Plutonium Fuel Rod 10 Plutonium Waste;</li>
 * <li>12: every Basin recipe of the pack has a time (Assembler, acid leaching, kitchen and jam recipes too);</li>
 * <li>17: FICSIT Blueprints Mk.2 unlocks a Blueprint Designer Mk.2, between Mk.1 and Mk.3 in size;</li>
 * <li>19: the Battery's texture, the prefab hint, Satisfactory's sink values for leaves, logs and mushrooms, Alien
 * Protein from any creature node, and the Traveler's Backpack version range.</li>
 * </ul>
 */
public final class DataFixTests {
    private static final int X = 10800, Y = 200, Z = -10800;
    private static final String COMPANY = "selftest_datafix";

    private record Later(long at, Runnable run) {
    }

    private static final List<Later> LATER = new ArrayList<>();
    private static int passed, failed, pending;

    private DataFixTests() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = server.overworld().getGameTime();
            for (Later l : new ArrayList<>(LATER)) {
                if (l.at > now) continue;
                LATER.remove(l);
                l.run.run();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LATER.clear());
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("datafix").executes(DataFixTests::run)))));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static void later(ServerLevel level, int ticks, Runnable run) {
        pending++;
        LATER.add(new Later(level.getServer().overworld().getGameTime() + ticks, () -> {
            run.run();
            pending--;
        }));
    }

    private static void report(CommandSourceStack source, String text) {
        String line = "SELFTEST datafix " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        report(source, (ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    private static Company company(ServerLevel level, String id, String... done) {
        CompanyData data = CompanyData.get(level.getServer());
        Company c = new Company();
        c.id = id;
        c.name = id;
        c.done.addAll(List.of(done));
        data.companies().put(id, c);
        data.setDirty();
        return c;
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    private static @Nullable RecipeHolder<?> recipe(MinecraftServer server, String id) {
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) if (holder.id().identifier().toString().equals(id)) return holder;
        return null;
    }

    private static @Nullable String lockOf(MinecraftServer server, String id) {
        RecipeHolder<?> holder = recipe(server, id);
        return holder == null ? "missing" : RecipeLocks.lockOf(server, holder.value());
    }

    /** One of the mod's own resource files, read as JSON. */
    private static @Nullable JsonObject resource(String path) {
        try (var in = Siftec.class.getResourceAsStream(path)) {
            return in == null ? null : new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** The mod's own fabric.mod.json: every mod has one at the same path, so the one whose id is siftec. */
    private static @Nullable JsonObject modJson() {
        try {
            var all = Siftec.class.getClassLoader().getResources("fabric.mod.json");
            while (all.hasMoreElements()) {
                try (var in = all.nextElement().openStream()) {
                    JsonObject o = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
                    if (o != null && o.has("id") && o.get("id").getAsString().equals("siftec")) return o;
                }
            }
        } catch (Exception ignored) {
            // reported as missing below
        }
        return null;
    }

    private static int count(Container c, Item item) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
        return n;
    }

    // ------------------------------------------------------------------------------------------------ the test

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        passed = failed = pending = 0;
        BuildingTests.platform(level, X - 8, Z - 8, X + 24, Z + 24, Y, 8);
        later(level, 10, () -> {
            run(source, "4 alternates", () -> alternates(source, level));
            run(source, "5 recipes", () -> recipes(source, level));
            run(source, "11 nuclear waste", () -> waste(source, level));
            run(source, "12 basin times", () -> times(source, level));
            run(source, "17 designer mk2", () -> designer(source, level));
            run(source, "19 small fixes", () -> small(source, level));
            finish(source, level);
        });
        report(source, "test site ready; the checks report over the next few seconds");
        return 1;
    }

    private static void run(CommandSourceStack source, String part, Runnable checks) {
        try {
            checks.run();
        } catch (RuntimeException e) {
            Siftec.LOGGER.error("SIFTEC: selftest datafix {} threw", part, e);
            expect(source, part + " runs to the end", false, e.toString());
        }
    }

    private static void finish(CommandSourceStack source, ServerLevel level) {
        if (pending > 0) {
            LATER.add(new Later(level.getServer().overworld().getGameTime() + 10, () -> finish(source, level)));
            return;
        }
        report(source, "done: " + passed + " passed, " + failed + " failed");
    }

    // ------------------------------------------------------------------------------------------------ 4

    /**
     * Pure Iron Ingot runs in the Refinery (Oil Processing). A company with everything else it once needed (the
     * Constructor, Coal Power, Basic Steel Production) but no Refinery is not offered it; with the Refinery it is.
     */
    private static void alternates(CommandSourceStack source, ServerLevel level) {
        String alt = "alt_pure_iron_ingot";
        expect(source, "Pure Iron Ingot is listed as a Refinery alternate", Alternates.building(alt).equals("refinery"), Alternates.building(alt));
        Company before = company(level, COMPANY + "_norefinery", "hub_upgrade_3", "coal_power", "basic_steel_production");
        expect(source, "no Refinery: Pure Iron Ingot is not offered", !Alternates.eligible(before).contains(alt), Alternates.eligible(before).toString());
        Company after = company(level, COMPANY + "_refinery", "hub_upgrade_3", "coal_power", "basic_steel_production", "oil_processing");
        expect(source, "with the Refinery: Pure Iron Ingot is offered", Alternates.eligible(after).contains(alt), "");
        // whatever a company has, every alternate it is offered runs in a building it has unlocked
        Company some = company(level, COMPANY + "_some", "hub_upgrade_1", "hub_upgrade_2", "hub_upgrade_3", "hub_upgrade_4", "hub_upgrade_5",
            "hub_upgrade_6", "part_assembly", "basic_steel_production", "coal_power", "mam_caterium_brass");
        List<String> wrong = new ArrayList<>();
        for (String id : Alternates.eligible(some)) if (!Alternates.buildingUnlocked(some, id)) wrong.add(id + "@" + Alternates.building(id));
        expect(source, "a Tier 1-3 company is offered only alternates its buildings run", wrong.isEmpty() && !Alternates.eligible(some).isEmpty(),
            wrong + " of " + Alternates.eligible(some).size());
    }

    // ------------------------------------------------------------------------------------------------ 5

    private static void recipes(CommandSourceStack source, ServerLevel level) {
        MinecraftServer server = level.getServer();
        expect(source, "Create's iron and copper ingot pressing is removed",
            recipe(server, "create:pressing/iron_ingot") == null && recipe(server, "create:pressing/copper_ingot") == null, "");
        if (FabricLoader.getInstance().isModLoaded("createdieselgenerators")) {
            String lock = lockOf(server, "createdieselgenerators:mixing/biodiesel");
            expect(source, "Diesel Generators' biodiesel needs Fluid Packaging", "fluid_packaging".equals(lock), String.valueOf(lock));
        }
        // every other mod's sequenced assembly: each step has the lock its final result has
        int lines = 0;
        List<String> wrong = new ArrayList<>();
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            if (holder.id().identifier().getNamespace().equals("siftec") || !(holder.value() instanceof SequencedAssemblyRecipe line)) continue;
            Milestone m = Locks.lockOf(line.result().item().value());
            String want = RecipeLocks.lockOf(server, line);
            if (want == null && m != null) want = m.id();
            lines++;
            for (Recipe<?> step : line.sequence()) {
                String got = RecipeLocks.lockOf(server, step);
                if (want == null ? got != null : !want.equals(got)) wrong.add(holder.id().identifier() + " step " + got + " want " + want);
            }
        }
        expect(source, "other mods' sequenced assembly steps are locked as their final result", lines > 0 && wrong.isEmpty(), lines + " lines " + wrong);
        RecipeHolder<?> mechanism = null;
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            if (holder.value() instanceof SequencedAssemblyRecipe line && !holder.id().identifier().getNamespace().equals("siftec")
                && BuiltInRegistries.ITEM.getKey(line.result().item().value()).toString().equals("create:precision_mechanism")) mechanism = holder;
        }
        if (mechanism != null) {
            String first = RecipeLocks.lockOf(server, ((SequencedAssemblyRecipe) mechanism.value()).sequence().getFirst());
            expect(source, "Precision Mechanism's first step needs Caterium Electronics", "mam_caterium_caterium_electronics".equals(first), String.valueOf(first));
        }
    }

    // ------------------------------------------------------------------------------------------------ 11

    private static void waste(CommandSourceStack source, ServerLevel level) {
        int[] got = new int[2];
        String[][] rods = {{"siftec:uranium_fuel_rod", "siftec:uranium_waste"}, {"siftec:plutonium_fuel_rod", "siftec:plutonium_waste"}};
        for (int k = 0; k < 2; k++) {
            BlockPos acceptor = new BlockPos(X + 2 + 4 * k, Y + 1, Z + 2), chest = acceptor.east();
            level.setBlock(acceptor, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(chest, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            BuildingPower.reactorWaste(level, acceptor, new ItemStack(item(rods[k][0])), 1);
            got[k] = level.getBlockEntity(chest) instanceof Container c ? count(c, item(rods[k][1])) : -1;
            level.setBlock(chest, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        expect(source, "a Uranium Fuel Rod leaves 50 Uranium Waste", got[0] == 50, String.valueOf(got[0]));
        expect(source, "a Plutonium Fuel Rod leaves 10 Plutonium Waste", got[1] == 10, String.valueOf(got[1]));
    }

    // ------------------------------------------------------------------------------------------------ 12

    private static void times(CommandSourceStack source, ServerLevel level) {
        MinecraftServer server = level.getServer();
        int basin = 0;
        List<String> untimed = new ArrayList<>();
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            Identifier id = holder.id().identifier();
            if (!id.getNamespace().equals("siftec") || !(id.getPath().startsWith("mixing/") || id.getPath().startsWith("compacting/"))) continue;
            basin++;
            if (MachineTimes.ticks(server, holder.value()) <= 0) untimed.add(id.getPath());
        }
        expect(source, "every Basin recipe of the pack has a time", basin > 0 && untimed.isEmpty(), basin + " recipes, untimed " + untimed);
        String[][] want = {{"siftec:mixing/assembler/ender_eye", "240"}, {"siftec:mixing/acid_leached_iron", "24"}, {"siftec:mixing/apple_jam", "200"},
            {"siftec:mixing/gunpowder_with_coal", "80"}, {"siftec:mixing/kitchen_beef_stew", "200"}};
        StringBuilder said = new StringBuilder();
        boolean ok = true;
        for (String[] w : want) {
            RecipeHolder<?> holder = recipe(server, w[0]);
            if (holder == null) continue;   // the kitchen recipes load with Farmer's Delight
            int t = MachineTimes.ticks(server, holder.value());
            ok &= t == Integer.parseInt(w[1]);
            said.append(' ').append(w[0]).append('=').append(t);
        }
        expect(source, "Assembler, leaching, jam, gunpowder and kitchen times are Satisfactory's", ok, said.toString().trim());
    }

    // ------------------------------------------------------------------------------------------------ 17

    private static void designer(CommandSourceStack source, ServerLevel level) {
        // the Blueprint Designers are Build Gun buildings now (the volumes are checked by /siftec selftest designer)
        BuildingDef mk2 = Buildings.def("blueprint_designer_mk2");
        expect(source, "FICSIT Blueprints Mk.2 unlocks the Blueprint Designer Mk.2 (a Build Gun building on the Special tab)",
            mk2 != null && mk2.unlock().equals("ficsit_blueprints_mk2") && mk2.category() == Category.SPECIAL,
            mk2 == null ? "no building" : mk2.unlock() + " " + mk2.category());
        boolean built = false;
        for (Milestones.Build b : Milestones.workshop()) built |= b.item().toString().startsWith("siftec:blueprint_designer");
        expect(source, "the Equipment Workshop builds no Blueprint Designer (the Build Gun does)", !built, "");
        int[] reach = new int[3];
        Block[] marks = {ModBlocks.BLUEPRINT_DESIGNER.get(), ModBlocks.BLUEPRINT_DESIGNER_MK2.get(), ModBlocks.BLUEPRINT_DESIGNER_MK3.get()};
        for (int k = 0; k < 3; k++) reach[k] = marks[k] instanceof DesignerBlock d ? d.reach : -1;
        expect(source, "Mk.2's build volume is between Mk.1's and Mk.3's", reach[1] == 6 && reach[0] < reach[1] && reach[1] < reach[2],
            "reach " + reach[0] + ", " + reach[1] + ", " + reach[2]);
    }

    // ------------------------------------------------------------------------------------------------ 19

    private static void small(CommandSourceStack source, ServerLevel level) {
        JsonObject battery = resource("/assets/siftec/models/item/battery.json");
        String layer = battery == null ? "missing" : battery.getAsJsonObject("textures").get("layer0").getAsString();
        expect(source, "the Battery's texture exists in 26.3 (not the torch's item texture)", layer.equals("minecraft:item/redstone"), layer);
        JsonObject lang = resource("/assets/siftec/lang/en_us.json");
        String hint = lang == null || !lang.has("siftec.prefab.hint") ? "missing" : lang.get("siftec.prefab.hint").getAsString();
        expect(source, "the prefab hint says R rotates", hint.contains("R rotates") && !hint.contains("Sneak"), hint);
        long leaves = Sink.worthOne(new ItemStack(Items.OAK_LEAVES)), log = Sink.worthOne(new ItemStack(Items.OAK_LOG));
        long red = Sink.worthOne(new ItemStack(Items.RED_MUSHROOM)), brown = Sink.worthOne(new ItemStack(Items.BROWN_MUSHROOM));
        expect(source, "the Sink pays Satisfactory's Leaves 3, Wood 30, Mycelia 10", leaves == 3 && log == 30 && red == 10 && brown == 10,
            "leaves " + leaves + ", log " + log + ", mushrooms " + red + "/" + brown);
        Milestone protein = Locks.lockOf(item("siftec:alien_protein"));
        MinecraftServer server = level.getServer();
        String[][] nodes = {{"hog", "mam_megafauna_zombie_research"}, {"spitter", "mam_megafauna_skeleton_research"},
            {"stinger", "mam_megafauna_spider_research"}, {"hatcher", "mam_megafauna_creeper_research"}};
        boolean each = true;
        StringBuilder said = new StringBuilder();
        for (String[] n : nodes) {
            String lock = lockOf(server, "siftec:compacting/constructor/" + n[0] + "_protein");
            each &= n[1].equals(lock);
            said.append(' ').append(n[0]).append('=').append(lock);
        }
        expect(source, "Alien Protein comes from any of the four creature nodes", protein == null && each,
            "item lock " + (protein == null ? "none" : protein.id()) + said);
        JsonObject mod = modJson();
        String range = mod == null || !mod.has("recommends") ? "missing" : mod.getAsJsonObject("recommends").get("travelersbackpack").getAsString();
        boolean breaks = mod != null && mod.has("breaks") && mod.getAsJsonObject("breaks").has("travelersbackpack");
        expect(source, "the Traveler's Backpack version range is pinned to 11.4.x", range.contains("<11.5") && breaks, range);
    }
}
