package com.meakaandre.siftec.command;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.fluid.ModFluids;
import com.meakaandre.siftec.food.Food;
import com.meakaandre.siftec.food.PreserveItem;
import com.meakaandre.siftec.hub.Locks;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.owner.RecipeLocks;
import com.meakaandre.siftec.tweak.NetherWater;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.content.processing.basin.BasinRecipe;
import com.zurrtum.create.content.processing.recipe.HeatCondition;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * /siftec selftest food (work package 11): the Food and Brass tab after the recipe rewrite. Food: no hunger, eating
 * is the only heal and heals more the more prepared the food, the twelve ingredient effects carry into what is made
 * from them, preserves are stronger and throwable, meals stack every effect, honey cleanses, and the secret
 * ingredients; the Nutrients research locks. Brass: Create's recipe gone, brass blend from a flux with no heat, at the
 * Caterium tree's Brass node. Heat: nothing superheated is left. Tweaks: gunpowder, Ignimbrite and lava, the Block of
 * Sulfur, no sulfur loop, Nether water. Every check prints "SELFTEST food"; failures say FAIL.
 */
public final class FoodTests {
    private static final String PRESERVED = "mam_nutrients_preserved_foods", KITCHEN = "mam_nutrients_automated_cooking",
        FERMENTING = "mam_nutrients_bulk_fermentation", CIDER = "mam_nutrients_cider_and_mead", BRASS = "mam_caterium_brass";
    private static int passed, failed;

    private FoodTests() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("food").executes(FoodTests::run)))));
    }

    private static void report(CommandSourceStack source, String text) {
        String line = "SELFTEST food " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        report(source, (ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(Items.AIR);
    }

    private static Recipe<?> recipe(MinecraftServer server, String id) {
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            if (holder.id().identifier().toString().equals(id)) return holder.value();
        }
        return null;
    }

    private static String lock(MinecraftServer server, String id) {
        Recipe<?> r = recipe(server, id);
        return r == null ? "missing" : String.valueOf(RecipeLocks.lockOf(server, r));
    }

    private static HeatCondition heat(MinecraftServer server, String id) {
        return recipe(server, id) instanceof BasinRecipe basin ? basin.heat() : null;
    }

    /** How many of an item a recipe makes, from what it shows (0 if it makes something else or is missing). */
    private static int makes(MinecraftServer server, ServerLevel level, String id, Item what) {
        Recipe<?> r = recipe(server, id);
        if (r == null) return 0;
        int n = 0;
        for (RecipeDisplay display : r.display()) {
            for (ItemStack stack : display.result().resolveForStacks(SlotDisplayContext.fromLevel(level))) {
                if (stack.is(what)) n = Math.max(n, stack.getCount());
            }
        }
        return n;
    }

    private static String profile(ServerLevel level, String id) {
        Food.Profile p = Food.of(level, new ItemStack(item(id)));
        return p.prep() + " " + p.intrinsics();
    }

    /** Eats one of the item as a player at 1 health with no effects: what it healed and what it gave. */
    private static float eat(FakePlayer player, ItemStack stack) {
        player.removeAllEffects();
        player.setHealth(1.0f);
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null) return -1;
        Food.onEat(player, stack, food);
        return player.getHealth() - 1.0f;
    }

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ServerLevel level = server.overworld();
        passed = 0;
        failed = 0;
        boolean fd = FabricLoader.getInstance().isModLoaded("farmersdelight");
        FakePlayer player = FakePlayer.get(level);

        // ---- food: no hunger, eating is the only heal
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5);
        Food.hold(player);
        expect(source, "the food bar is held at 17 with no saturation: sprinting is free (over 6), natural regeneration never starts (18)",
            Food.HELD_FOOD_LEVEL == 17 && player.getFoodData().getFoodLevel() == 17 && player.getFoodData().getSaturationLevel() == 0,
            "level " + player.getFoodData().getFoodLevel() + ", saturation " + player.getFoodData().getSaturationLevel());
        boolean prepOrder = Food.Prep.RAW.healMultiplier <= Food.Prep.SIMPLE.healMultiplier && Food.Prep.SIMPLE.healMultiplier < Food.Prep.PRESERVED.healMultiplier
            && Food.Prep.PRESERVED.healMultiplier < Food.Prep.MEAL.healMultiplier && Food.Prep.SIMPLE.duration < Food.Prep.PRESERVED.duration
            && Food.Prep.PRESERVED.amplifier > Food.Prep.SIMPLE.amplifier && Food.Prep.RAW.duration == 0;
        expect(source, "simple, preserved and meal heal more in that order; preserved effects are stronger and longer than simple ones; raw gives none",
            prepOrder, "");

        // ---- the twelve ingredients and what carries them
        Map<Food.Intrinsic, String> effects = Map.ofEntries(Map.entry(Food.Intrinsic.TOMATO, "strength"), Map.entry(Food.Intrinsic.ONION, "fire_resistance"),
            Map.entry(Food.Intrinsic.CABBAGE, "regeneration"), Map.entry(Food.Intrinsic.PUMPKIN, "resistance"), Map.entry(Food.Intrinsic.CARROT, "haste"),
            Map.entry(Food.Intrinsic.SWEET_BERRIES, "speed"), Map.entry(Food.Intrinsic.GLOW_BERRIES, "night_vision"), Map.entry(Food.Intrinsic.BEETROOT, "jump_boost"),
            Map.entry(Food.Intrinsic.KELP, "water_breathing"), Map.entry(Food.Intrinsic.APPLE, "absorption"), Map.entry(Food.Intrinsic.MELON, "slow_falling"));
        StringBuilder wrong = new StringBuilder();
        for (Food.Intrinsic in : Food.Intrinsic.values()) {
            String want = effects.get(in);
            String have = in.effect == null ? null : in.effect.unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
            if (want == null ? have != null : !want.equals(have)) wrong.append(' ').append(in).append('=').append(have);
            if (in.item() == Items.AIR && (fd || !in.name().matches("TOMATO|ONION|CABBAGE"))) wrong.append(' ').append(in).append(" has no item");
        }
        expect(source, "twelve ingredients carry the tab's effects, honey cleanses instead", Food.Intrinsic.values().length == 12 && wrong.isEmpty(), wrong.toString().trim());
        expect(source, "filling bases carry no effect: bread, baked potato, cooked beef",
            profile(level, "minecraft:bread").equals("RAW []") && profile(level, "minecraft:baked_potato").equals("RAW []") && profile(level, "minecraft:cooked_beef").equals("RAW []"),
            profile(level, "minecraft:bread") + "; " + profile(level, "minecraft:baked_potato") + "; " + profile(level, "minecraft:cooked_beef"));
        expect(source, "simple food made from an ingredient carries it: golden carrot, pumpkin pie",
            profile(level, "minecraft:golden_carrot").equals("SIMPLE [CARROT]") && profile(level, "minecraft:pumpkin_pie").equals("SIMPLE [PUMPKIN]"),
            profile(level, "minecraft:golden_carrot") + "; " + profile(level, "minecraft:pumpkin_pie"));
        boolean preserves = Food.PRESERVES.size() == 12;
        StringBuilder pres = new StringBuilder();
        for (var e : Food.PRESERVES.entrySet()) {
            Item jar = item("siftec:" + e.getKey());
            Food.Profile p = Food.of(level, new ItemStack(jar));
            boolean ok = jar instanceof PreserveItem pi && pi.intrinsic == e.getValue() && p.prep() == Food.Prep.PRESERVED && p.intrinsics().equals(Set.of(e.getValue()))
                && new ItemStack(jar).has(DataComponents.FOOD);
            if (!ok) pres.append(' ').append(e.getKey()).append('=').append(p.prep()).append(p.intrinsics());
            preserves &= ok;
        }
        expect(source, "seven pickles, four jams and mead are Preserved, each with its ingredient's effect, and throwable jars", preserves, pres.toString().trim());
        if (fd) {
            String sauce = profile(level, "farmersdelight:tomato_sauce"), rata = profile(level, "farmersdelight:ratatouille"), stew = profile(level, "farmersdelight:fish_stew");
            Food.Profile r = Food.of(level, new ItemStack(item("farmersdelight:ratatouille"))), s = Food.of(level, new ItemStack(item("farmersdelight:fish_stew")));
            expect(source, "cooking pot meals are Meals and stack every ingredient's effect: tomato sauce, ratatouille, fish stew (through its tomato sauce)",
                sauce.equals("MEAL [TOMATO]") && r.prep() == Food.Prep.MEAL && r.intrinsics().containsAll(Set.of(Food.Intrinsic.TOMATO, Food.Intrinsic.ONION))
                    && s.prep() == Food.Prep.MEAL && s.intrinsics().containsAll(Set.of(Food.Intrinsic.TOMATO, Food.Intrinsic.ONION)),
                sauce + "; " + rata + "; " + stew);
        } else {
            report(source, "Farmer's Delight is not loaded: the cooking pot checks are skipped");
        }

        // ---- eating
        ItemStack carrot = new ItemStack(Items.CARROT);
        float carrotHeal = eat(player, carrot);
        boolean carrotPlain = player.getActiveEffects().isEmpty();
        expect(source, "a raw carrot heals its nutrition and gives no effect", Math.abs(carrotHeal - carrot.get(DataComponents.FOOD).nutrition()) < 0.01f && carrotPlain,
            "healed " + carrotHeal + ", effects " + player.getActiveEffects().size());
        ItemStack goldenCarrot = new ItemStack(Items.GOLDEN_CARROT);
        eat(player, goldenCarrot);
        MobEffectInstance haste = player.getEffect(MobEffects.HASTE);
        expect(source, "a golden carrot (simple) gives Haste I for 30 seconds", haste != null && haste.getAmplifier() == 0 && haste.getDuration() >= Food.Prep.SIMPLE.duration - 2,
            haste == null ? "no haste" : "haste " + haste.getAmplifier() + " for " + haste.getDuration());
        Item pickledTomato = item("siftec:pickled_tomato");
        if (pickledTomato != Items.AIR) {
            ItemStack jar = new ItemStack(pickledTomato);
            float healed = eat(player, jar);
            MobEffectInstance strength = player.getEffect(MobEffects.STRENGTH);
            float want = Math.min(player.getMaxHealth() - 1.0f, jar.get(DataComponents.FOOD).nutrition() * Food.Prep.PRESERVED.healMultiplier);
            expect(source, "a pickled tomato heals one and a half times its nutrition and gives Strength II for 90 seconds",
                Math.abs(healed - want) < 0.01f && strength != null && strength.getAmplifier() == 1 && strength.getDuration() >= Food.Prep.PRESERVED.duration - 2,
                "healed " + healed + " of " + want + ", " + (strength == null ? "no strength" : "strength " + strength.getAmplifier() + " for " + strength.getDuration()));
            ItemStack strong = new ItemStack(pickledTomato), longer = new ItemStack(pickledTomato);
            CustomData.update(DataComponents.CUSTOM_DATA, strong, tag -> tag.putString("siftec_season", "strong"));
            CustomData.update(DataComponents.CUSTOM_DATA, longer, tag -> tag.putString("siftec_season", "long"));
            eat(player, strong);
            MobEffectInstance s2 = player.getEffect(MobEffects.STRENGTH);
            eat(player, longer);
            MobEffectInstance l2 = player.getEffect(MobEffects.STRENGTH);
            expect(source, "blaze powder makes the effect a level stronger, glowstone makes it last twice as long",
                s2 != null && s2.getAmplifier() == 2 && l2 != null && l2.getAmplifier() == 1 && l2.getDuration() >= 2 * Food.Prep.PRESERVED.duration - 2,
                (s2 == null ? "none" : "strong " + s2.getAmplifier()) + ", " + (l2 == null ? "none" : "long " + l2.getDuration()));
            ItemStack bonus = new ItemStack(Items.BREAD);
            CustomData.update(DataComponents.CUSTOM_DATA, bonus, tag -> tag.putString("siftec_season", "bonus"));
            eat(player, bonus);
            expect(source, "nether wart adds a random bonus effect, even to plain bread", player.getActiveEffects().size() == 1, player.getActiveEffects().size() + " effects");
        }
        Item mead = item("siftec:mead");
        if (mead != Items.AIR) {
            player.removeAllEffects();
            player.setHealth(1.0f);
            player.addEffect(new MobEffectInstance(MobEffects.POISON, 200));
            player.addEffect(new MobEffectInstance(MobEffects.SPEED, 200));
            ItemStack cup = new ItemStack(mead);
            Food.onEat(player, cup, cup.get(DataComponents.FOOD));
            expect(source, "mead (honey) removes bad effects and keeps good ones", !player.hasEffect(MobEffects.POISON) && player.hasEffect(MobEffects.SPEED), "");
        }
        player.removeAllEffects();
        player.setHealth(player.getMaxHealth());

        // ---- the Nutrients research
        Milestone kitchen = Milestones.get(KITCHEN);
        expect(source, "the Automated Cooking research gives cooking pot meals on the heated Mixer and the secret ingredients",
            kitchen != null && kitchen.tokens().contains("food:kitchen") && kitchen.tokens().contains("food:seasoning"), kitchen == null ? "no node" : kitchen.tokens().toString());
        List<String> pickles = new ArrayList<>();
        for (String id : new String[]{"siftec:filling/pickled_tomato", "siftec:filling/pickled_kelp", "siftec:mixing/sweet_berry_jam", "siftec:mixing/melon_jam"}) {
            pickles.add(id.substring(id.lastIndexOf('/') + 1) + "=" + lock(server, id));
        }
        expect(source, "pickles and jams need the Preserved Foods research", pickles.stream().allMatch(p -> p.endsWith("=" + PRESERVED)), String.join(" ", pickles));
        String vinegar = lock(server, "siftec:bulk_fermenting/vinegar"), bottle = lock(server, "siftec:filling/vinegar_bottle"),
            cider = lock(server, "siftec:bulk_fermenting/cider"), meadLock = lock(server, "siftec:bulk_fermenting/mead");
        expect(source, "vinegar needs Bulk Fermentation, cider and mead need Cider and Mead",
            vinegar.equals(FERMENTING) && bottle.equals(FERMENTING) && cider.equals(CIDER) && meadLock.equals(CIDER), vinegar + ", " + bottle + ", " + cider + ", " + meadLock);
        if (fd) {
            String meal = lock(server, "siftec:mixing/kitchen_ratatouille"), knife = lock(server, "siftec:deploying/kitchen_rice_panicle");
            HeatCondition h = heat(server, "siftec:mixing/kitchen_ratatouille");
            expect(source, "cooking pot meals run on a heated Mixer and knife recipes on a Deployer, after Automated Cooking",
                meal.equals(KITCHEN) && knife.equals(KITCHEN) && h == HeatCondition.HEATED, meal + ", " + knife + ", heat " + h);
        }

        // ---- brass, the pack's Caterium
        Item blend = item("siftec:brass_blend");
        int sand = makes(server, level, "siftec:mixing/brass_blend_with_sand", blend), coal = makes(server, level, "siftec:mixing/brass_blend_with_coal", blend),
            sulfur = makes(server, level, "siftec:mixing/brass_blend_with_sulfur", blend);
        HeatCondition blendHeat = heat(server, "siftec:mixing/brass_blend_with_sand");
        expect(source, "brass blend from crushed copper, crushed zinc and a flux, with no heat: sand 1, coal 2, sulfur 3",
            sand == 1 && coal == 2 && sulfur == 3 && blendHeat == HeatCondition.NONE, "sand " + sand + ", coal " + coal + ", sulfur " + sulfur + ", heat " + blendHeat);
        boolean smelts = makes(server, level, "siftec:smelting/brass_ingot_from_blend", item("create:brass_ingot")) == 1
            && makes(server, level, "siftec:blasting/brass_ingot_from_blend", item("create:brass_ingot")) == 1;
        expect(source, "the blend smelts into brass, in a furnace or by fan", smelts, "");
        expect(source, "Create's own brass recipe is gone", recipe(server, "create:mixing/brass_ingot") == null && Milestones.removedRecipes().contains(Identifier.parse("create:mixing/brass_ingot")), "");
        Milestone ingotLock = Locks.lockOf(item("create:brass_ingot")), wheels = Locks.lockOf(item("create:crushing_wheel"));
        String blendLock = lock(server, "siftec:mixing/brass_blend_with_sand");
        expect(source, "brass needs the Caterium tree's Brass node, and its crushed ore the Crushing Wheels of Advanced Steel Production (Tier 4)",
            ingotLock != null && ingotLock.id().equals(BRASS) && blendLock.equals(BRASS) && wheels != null && wheels.id().equals("advanced_steel_production") && wheels.tier() == 4,
            "ingot " + (ingotLock == null ? "free" : ingotLock.id()) + ", blend " + blendLock + ", wheels " + (wheels == null ? "free" : wheels.id() + " tier " + wheels.tier()));
        Milestone caterium = Milestones.get("mam_caterium_caterium");
        expect(source, "the Caterium tree starts from crushed zinc", caterium != null && !caterium.cost().isEmpty() && caterium.cost().get(0).key().equals("create:crushed_raw_zinc"),
            caterium == null ? "no node" : caterium.cost().toString());

        // ---- heat
        List<String> superheated = new ArrayList<>();
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            if (holder.value() instanceof BasinRecipe basin && basin.heat() == HeatCondition.SUPERHEATED) superheated.add(holder.id().identifier().toString());
        }
        expect(source, "no loaded recipe needs superheating", superheated.isEmpty(), String.join(" ", superheated));
        expect(source, "Blaze Cake is disabled", Locks.lockOf(item("create:blaze_cake")) == Locks.DISABLED && Locks.lockOf(item("create:creative_blaze_cake")) == Locks.DISABLED, "");
        Recipe<?> circuit = recipe(server, "siftec:compacting/blank_circuit");
        expect(source, "New Age's superheated Blank Circuit is a heated recipe that also takes diesel",
            circuit instanceof BasinRecipe b && b.heat() == HeatCondition.HEATED && !b.fluidIngredients().isEmpty() && recipe(server, "create_new_age:compacting/blank_circuit") == null,
            circuit == null ? "missing" : "heat " + heat(server, "siftec:compacting/blank_circuit"));
        expect(source, "steel comes from the Foundry, not Gunsmithing's superheated recipe",
            recipe(server, "cgs:mixing/steel_ingot") == null && recipe(server, "siftec:mixing/foundry/steel_ingot") != null, "");

        // ---- the other tweaks carried over
        String coalPowder = lock(server, "siftec:mixing/gunpowder_with_coal"), charcoalPowder = lock(server, "siftec:mixing/gunpowder_with_charcoal");
        expect(source, "gunpowder from sulfur, coal or charcoal and bone meal, after Black Powder",
            coalPowder.equals("mam_sulfur_black_powder") && charcoalPowder.equals("mam_sulfur_black_powder") && makes(server, level, "siftec:mixing/gunpowder_with_coal", Items.GUNPOWDER) == 3,
            coalPowder + ", " + charcoalPowder);
        expect(source, "Ignimbrite is a fluid from cobblestone and sulfur in a heated mix, and makes fire charges, magma blocks and TNT",
            ModFluids.ALL.containsKey("ignimbrite") && heat(server, "siftec:mixing/ignimbrite") == HeatCondition.HEATED
                && makes(server, level, "siftec:filling/fire_charge", Items.FIRE_CHARGE) == 1 && makes(server, level, "siftec:compacting/magma_block", Items.MAGMA_BLOCK) == 1
                && makes(server, level, "siftec:filling/tnt", Items.TNT) == 1, "");
        expect(source, "magma melts to lava; no lava from cobblestone, no sulfur from crushing magma, no Potent Sulfur",
            heat(server, "siftec:mixing/lava_from_magma_block") == HeatCondition.HEATED && recipe(server, "create:mixing/lava_from_cobble") == null
                && recipe(server, "createdieselgenerators:bulk_fermenting/lava") == null && recipe(server, "create:crushing/magma_block") == null
                && recipe(server, "minecraft:potent_sulfur") == null, "");
        expect(source, "the Block of Sulfur packs four sulfur and back",
            makes(server, level, "siftec:crafting/block_of_sulfur", item("minecraft:sulfur")) == 1 && makes(server, level, "siftec:crafting/sulfur_from_block", item("cgs:sulfur")) == 4, "");
        expect(source, "water boils away in the Nether only", NetherWater.hot(server.getLevel(Level.NETHER)) && !NetherWater.hot(level), "");

        report(source, "done: " + passed + " passed, " + failed + " failed");
        return failed == 0 ? 1 : 0;
    }
}
