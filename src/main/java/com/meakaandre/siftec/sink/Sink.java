package com.meakaandre.siftec.sink;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.Milestones;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The AWESOME Sink's rules (AWESOME Sink and Shop tab): what each item is worth, what it refuses, and the FICSIT Coupon
 * curve. Every Satisfactory part is worth Satisfactory's value; vanilla and other mods' items the tab's values (by id,
 * then by tag), and anything else its Minecraft rarity; every enchantment level adds to that. The points go to the
 * company, and any member prints them as coupons at the Sink.
 */
public final class Sink {
    private static @Nullable Map<String, Integer> points;
    private static final List<Map.Entry<TagKey<Item>, Integer>> TAGS = new ArrayList<>();
    private static final Map<String, Integer> RARITY = new HashMap<>();
    private static final Set<String> REFUSED = new HashSet<>();
    private static int enchant, first, firstCost, last;
    private static long cap;

    private Sink() {
    }

    private static synchronized void load() {
        if (points != null) return;
        JsonObject raw = Milestones.raw();
        Map<String, Integer> table = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : raw.getAsJsonObject("sink_points").entrySet()) table.put(e.getKey(), e.getValue().getAsInt());
        for (JsonElement e : raw.getAsJsonArray("sink_tags")) {
            JsonObject o = e.getAsJsonObject();
            TAGS.add(Map.entry(TagKey.create(Registries.ITEM, Identifier.parse(o.get("tag").getAsString())), o.get("points").getAsInt()));
        }
        for (Map.Entry<String, JsonElement> e : raw.getAsJsonObject("sink_rarity").entrySet()) RARITY.put(e.getKey(), e.getValue().getAsInt());
        for (JsonElement e : raw.getAsJsonArray("sink_refused")) REFUSED.add(e.getAsString());
        enchant = raw.get("sink_enchant").getAsInt();
        JsonObject c = raw.getAsJsonObject("coupons");
        first = c.get("first").getAsInt();
        firstCost = c.get("first_cost").getAsInt();
        last = c.get("last").getAsInt();
        cap = c.get("cap").getAsLong();
        points = table;
    }

    /** Everything but the radioactive and toxic waste (which has to be dealt with properly) and coupons. */
    public static boolean accepts(ItemStack stack) {
        load();
        return !stack.isEmpty() && !REFUSED.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    /** Points for one of this item: its listed value, a tag's, or its rarity's; plus its enchantment levels. */
    public static long worthOne(ItemStack stack) {
        load();
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        Integer listed = points.get(id);
        long base;
        if (listed != null) {
            base = listed;
        } else {
            Integer byTag = null;
            for (Map.Entry<TagKey<Item>, Integer> t : TAGS) {
                if (stack.is(t.getKey())) {
                    byTag = t.getValue();
                    break;
                }
            }
            base = byTag != null ? byTag : RARITY.getOrDefault(stack.getRarity().name().toLowerCase(Locale.ROOT), 2);
        }
        return base + (long) enchant * (levels(stack.get(DataComponents.ENCHANTMENTS)) + levels(stack.get(DataComponents.STORED_ENCHANTMENTS)));
    }

    private static int levels(@Nullable ItemEnchantments enchantments) {
        if (enchantments == null) return 0;
        int n = 0;
        for (var e : enchantments.entrySet()) n += e.getIntValue();
        return n;
    }

    /** Points for a whole stack. */
    public static long worth(ItemStack stack) {
        return stack.getCount() * worthOne(stack);
    }

    /** What printing coupon n (counted from 1) costs: Satisfactory 1.0's curve. */
    public static long couponCost(int n) {
        load();
        if (n <= first) return firstCost;
        if (n <= last) {
            long step = (n + 2) / 3 - 1;
            return 250 * step * step + 1000;
        }
        return cap;
    }

    /** The company is paid for what its Sink took. */
    public static void pay(MinecraftServer server, String companyId, long amount) {
        if (credit(server, companyId, amount)) Companies.save(server);
    }

    /** Points a simulated Sink earned (every step while it runs): written with the next save within a minute. */
    public static void paySimulated(MinecraftServer server, String companyId, long amount) {
        if (credit(server, companyId, amount)) Companies.saveSoon(server);
    }

    private static boolean credit(MinecraftServer server, String companyId, long amount) {
        Company company = CompanyData.get(server).byId(companyId);
        if (company == null || amount <= 0) return false;
        company.points += amount;
        company.pointsEver += amount;
        return true;
    }

    /** Prints as many coupons as the company's points pay for, at most a stack at a time. Returns how many. */
    public static int print(Company company) {
        int made = 0;
        while (made < 64) {
            long cost = couponCost(company.couponsPrinted + 1);
            if (company.points < cost) break;
            company.points -= cost;
            company.couponsPrinted++;
            made++;
        }
        return made;
    }
}
