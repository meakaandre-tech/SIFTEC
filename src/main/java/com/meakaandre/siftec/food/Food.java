package com.meakaandre.siftec.food;

import com.meakaandre.siftec.Siftec;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The pack's food rules. There is no hunger: the food bar is held just under the line where health
 * regenerates on its own, so eating is the only way to heal. Twelve ingredients carry an effect, and any
 * food made from them carries it too; the more prepared the food, the bigger the heal and the effect.
 */
public final class Food {
    /** 17: enough to sprint, one short of natural regeneration (18). */
    public static final int HELD_FOOD_LEVEL = 17;

    /** The ingredients whose effect carries into anything made from them. */
    public enum Intrinsic {
        TOMATO(MobEffects.STRENGTH, "farmersdelight:tomato"), ONION(MobEffects.FIRE_RESISTANCE, "farmersdelight:onion"),
        CABBAGE(MobEffects.REGENERATION, "farmersdelight:cabbage"), PUMPKIN(MobEffects.RESISTANCE, "minecraft:pumpkin"),
        CARROT(MobEffects.HASTE, "minecraft:carrot"), SWEET_BERRIES(MobEffects.SPEED, "minecraft:sweet_berries"),
        GLOW_BERRIES(MobEffects.NIGHT_VISION, "minecraft:glow_berries"), BEETROOT(MobEffects.JUMP_BOOST, "minecraft:beetroot"),
        KELP(MobEffects.WATER_BREATHING, "minecraft:kelp"), APPLE(MobEffects.ABSORPTION, "minecraft:apple"),
        /** Removes bad effects instead of adding one. */
        HONEY(null, "minecraft:honey_bottle"), MELON(MobEffects.SLOW_FALLING, "minecraft:melon_slice");

        public final Holder<MobEffect> effect;
        private final Identifier itemId;

        Intrinsic(Holder<MobEffect> effect, String item) {
            this.effect = effect;
            this.itemId = Identifier.parse(item);
        }

        public Item item() {
            return BuiltInRegistries.ITEM.getOptional(itemId).orElse(Items.AIR);
        }
    }

    /** How far a food has been prepared: decides the heal and how strong its effects are. */
    public enum Prep {
        RAW(1.0f, 0, 0), SIMPLE(1.0f, 0, 30 * 20), PRESERVED(1.5f, 1, 90 * 20), MEAL(2.0f, 0, 120 * 20);

        public final float healMultiplier;
        public final int amplifier, duration;

        Prep(float healMultiplier, int amplifier, int duration) {
            this.healMultiplier = healMultiplier;
            this.amplifier = amplifier;
            this.duration = duration;
        }
    }

    public record Profile(Set<Intrinsic> intrinsics, Prep prep) {
        public static final Profile NONE = new Profile(Set.of(), Prep.RAW);
    }

    private static RecipeManager cachedFor;
    private static Map<Item, Profile> cache = Map.of();

    private Food() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(Food::holdFoodLevel);
    }

    private static void holdFoodLevel(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            var food = player.getFoodData();
            if (food.getFoodLevel() != HELD_FOOD_LEVEL) food.setFoodLevel(HELD_FOOD_LEVEL);
            if (food.getSaturationLevel() != 0) food.setSaturation(0);
        }
    }

    /** Called when anything finishes eating a food item. */
    public static void onEat(LivingEntity eater, ItemStack stack, FoodProperties food) {
        if (!(eater instanceof Player player) || player.level().isClientSide() || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) return;
        Profile profile = of(level, stack);
        player.heal(food.nutrition() * profile.prep().healMultiplier);
        if (profile.prep() == Prep.RAW) return;
        for (Intrinsic i : profile.intrinsics()) {
            if (i == Intrinsic.HONEY) {
                for (MobEffectInstance e : List.copyOf(player.getActiveEffects())) {
                    if (e.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) player.removeEffect(e.getEffect());
                }
            } else {
                player.addEffect(new MobEffectInstance(i.effect, profile.prep().duration, profile.prep().amplifier));
            }
        }
    }

    public static Profile of(net.minecraft.server.level.ServerLevel level, ItemStack stack) {
        if (stack.isEmpty()) return Profile.NONE;
        RecipeManager recipes = level.getServer().getRecipeManager();
        synchronized (Food.class) {
            if (recipes != cachedFor) {
                try {
                    cache = compute(recipes, level);
                } catch (RuntimeException e) {
                    Siftec.LOGGER.warn("SIFTEC could not read the recipes to work out food effects", e);
                    cache = Map.of();
                }
                cachedFor = recipes;
            }
        }
        return cache.getOrDefault(stack.getItem(), Profile.NONE);
    }

    private static boolean isFood(Item item) {
        return new ItemStack(item).has(DataComponents.FOOD);
    }

    /**
     * Follows the recipes: a food made from an intrinsic ingredient (or from a food that carries one) carries
     * it too, so cooked dishes and Farmer's Delight meals pick up everything in them by themselves.
     */
    private static Map<Item, Profile> compute(RecipeManager recipes, Level level) {
        Map<Item, Set<Intrinsic>> carries = new HashMap<>();
        Map<Item, Prep> prep = new HashMap<>();
        Set<Item> bases = new HashSet<>();
        for (Intrinsic in : Intrinsic.values()) {
            if (in.item() == Items.AIR) continue;
            carries.computeIfAbsent(in.item(), k -> EnumSet.noneOf(Intrinsic.class)).add(in);
            bases.add(in.item());
        }
        ContextMap context = SlotDisplayContext.fromLevel(level);
        record Parsed(List<Item> results, List<List<Item>> slots, boolean cookingPot) {
        }
        List<Parsed> parsed = new ArrayList<>();
        for (RecipeHolder<?> holder : recipes.getRecipes()) {
            try {
                List<Item> results = new ArrayList<>();
                for (RecipeDisplay display : holder.value().display()) {
                    for (ItemStack result : display.result().resolveForStacks(context)) {
                        if (!result.isEmpty() && isFood(result.getItem()) && !bases.contains(result.getItem())) results.add(result.getItem());
                    }
                }
                if (results.isEmpty()) continue;
                List<List<Item>> slots = new ArrayList<>();
                for (Ingredient ingredient : holder.value().placementInfo().ingredients()) {
                    slots.add(ingredient.items().map(Holder::value).toList());
                }
                Identifier type = BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType());
                parsed.add(new Parsed(results, slots, type != null && type.toString().equals("farmersdelight:cooking")));
            } catch (RuntimeException ignored) {
                // a recipe of a kind that cannot be read this way simply passes nothing on
            }
        }
        for (int pass = 0; pass < 6; pass++) {
            boolean changed = false;
            for (Parsed recipe : parsed) {
                Set<Intrinsic> found = EnumSet.noneOf(Intrinsic.class);
                for (List<Item> options : recipe.slots()) {
                    if (options.isEmpty()) continue;
                    // a slot only passes on what every item it accepts carries ("any vegetable" passes nothing)
                    Set<Intrinsic> common = EnumSet.noneOf(Intrinsic.class);
                    common.addAll(carries.getOrDefault(options.get(0), Set.of()));
                    for (int i = 1; i < options.size() && !common.isEmpty(); i++) common.retainAll(carries.getOrDefault(options.get(i), Set.of()));
                    found.addAll(common);
                }
                if (found.isEmpty()) continue;
                for (Item out : recipe.results()) {
                    Set<Intrinsic> have = carries.computeIfAbsent(out, k -> EnumSet.noneOf(Intrinsic.class));
                    if (have.addAll(found)) changed = true;
                    Prep p = recipe.cookingPot() || have.size() >= 2 ? Prep.MEAL : Prep.SIMPLE;
                    Prep old = prep.get(out);
                    if (old == null || p.ordinal() > old.ordinal()) {
                        prep.put(out, p);
                        changed = true;
                    }
                }
            }
            if (!changed) break;
        }
        Map<Item, Profile> out = new HashMap<>();
        for (var e : carries.entrySet()) {
            Prep p = prep.getOrDefault(e.getKey(), Prep.RAW);
            if (e.getValue().size() >= 2 && p != Prep.PRESERVED) p = Prep.MEAL;
            out.put(e.getKey(), new Profile(Set.copyOf(e.getValue()), p));
        }
        return out;
    }

    /** For the automated test. */
    public static String describe(net.minecraft.server.level.ServerLevel level, Item item) {
        Profile p = of(level, new ItemStack(item));
        return BuiltInRegistries.ITEM.getKey(item) + " " + p.prep() + " " + p.intrinsics();
    }
}
