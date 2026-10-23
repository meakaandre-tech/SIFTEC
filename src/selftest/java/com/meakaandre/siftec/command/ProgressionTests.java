package com.meakaandre.siftec.command;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.depot.DepotBlockEntity;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.prefab.Templates;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * /siftec selftest progression: the HUB's tiers and milestones, the Wormhole phases that open them and
 * free play after Phase 5, and the MAM's trees, their starting items, cross-tree and either-or needs. Works on throwaway
 * companies only. Every check prints "SELFTEST progression"; failures say FAIL.
 */
public final class ProgressionTests {
    private static int passed, failed;

    private ProgressionTests() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("progression").executes(ProgressionTests::run)))));
    }

    private static void report(CommandSourceStack source, String text) {
        String line = "SELFTEST progression " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        report(source, (ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    private static Company company(String... done) {
        Company c = new Company();
        c.id = "selftest_progression";
        c.name = c.id;
        c.done.addAll(List.of(done));
        return c;
    }

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        passed = 0;
        failed = 0;

        // ---- the HUB: tiers 0 to 9 with Satisfactory 1.0's milestones (48 with the four 1.0 added), phases opening tiers
        int milestones = 0;
        for (int t = 0; t < Milestones.TIERS; t++) milestones += Milestones.tier(t).size();
        expect(source, "48 HUB milestones in tiers 0 to 9, 6 HUB Upgrades in Tier 0", milestones == 48 && Milestones.tier(0).size() == 6,
            milestones + " milestones, " + Milestones.tier(0).size() + " in Tier 0");
        expect(source, "Contraption Transit replaces Hypertubes in Tier 4", Milestones.get("contraption_transit") != null && Milestones.get("hypertubes") == null
            && Milestones.get("contraption_transit").tier() == 4, "");
        Company fresh = company();
        Company upgraded = company("hub_upgrade_1", "hub_upgrade_2", "hub_upgrade_3", "hub_upgrade_4", "hub_upgrade_5", "hub_upgrade_6");
        expect(source, "Tier 0 in order: HUB Upgrade 2 waits for Upgrade 1", Milestones.blocker(fresh, Milestones.get("hub_upgrade_2")) == Milestones.get("hub_upgrade_1"), "");
        expect(source, "Tiers 1 and 2 open after HUB Upgrade 6, Tier 3 needs Wormhole Phase 1",
            Milestones.blocker(fresh, Milestones.get("base_building")) == Milestones.get("hub_upgrade_6") && Milestones.blocker(upgraded, Milestones.get("part_assembly")) == null
                && Milestones.blocker(upgraded, Milestones.get("coal_power")) == Milestones.get("phase_1"), "");
        boolean phasesOpen = true;
        int[][] opens = {{3, 1}, {4, 1}, {5, 2}, {6, 2}, {7, 3}, {8, 3}, {9, 4}};
        for (int[] o : opens) phasesOpen &= Milestones.phaseFor(o[0]) == o[1];
        expect(source, "Phase 1 opens Tiers 3-4, Phase 2 Tiers 5-6, Phase 3 Tiers 7-8, Phase 4 Tier 9", phasesOpen, "");
        Milestone phase5 = Milestones.phases().get(Milestones.phases().size() - 1);
        Company five = company("hub_upgrade_6", "phase_1", "phase_2", "phase_3", "phase_4");
        boolean before = Milestones.freePlay(five);
        five.done.add(phase5.id());
        expect(source, "free play once Phase 5 is delivered", Milestones.phases().size() == 5 && !before && Milestones.freePlay(five), phase5.id());
        expect(source, "belt Mk.1 comes with HUB Upgrade 4", Milestones.get("hub_upgrade_4").tokens().contains("belt:60"), "");
        // the backpack is worn from the start; four of Satisfactory's inventory-slot milestones raise its tier
        Map<String, String> tiers = new TreeMap<>();
        for (Milestone m : Milestones.all()) for (String t : m.tokens()) if (t.startsWith("backpack")) tiers.put(t, m.id());
        expect(source, "the backpack's tiers come with Field Research, Vehicular Transport, Jetpack and Hover Pack",
            tiers.equals(Map.of("backpack:iron", "field_research", "backpack:gold", "vehicular_transport", "backpack:diamond", "jetpack",
                "backpack:netherite", "hover_pack")), tiers.toString());

        // ---- the MAM: seven trees, every node 3 seconds, the starting items, and needs across trees and either-or
        List<Milestones.Tree> trees = Milestones.trees();
        int nodes = 0;
        boolean quick = true;
        Set<String> ids = new HashSet<>();
        for (Milestones.Tree t : trees) {
            for (Milestone m : t.nodes()) {
                nodes++;
                quick &= m.seconds() == 3;
                ids.add(m.id());
            }
        }
        boolean needsKnown = true;
        for (Milestones.Tree t : trees) for (Milestone m : t.nodes()) for (String need : m.needs()) for (String one : need.split("\\|")) needsKnown &= ids.contains(one);
        expect(source, "seven MAM trees, every node researched in 0:03, every need a real node", trees.size() == 7 && quick && needsKnown && nodes >= 90,
            trees.size() + " trees, " + nodes + " nodes");
        boolean noSlugs = true;
        for (Milestones.Tree t : trees) noSlugs &= !t.id().equals("slugs");
        expect(source, "the Power Slugs tree is gone", noSlugs, "");
        Milestones.Tree megafauna = trees.stream().filter(t -> t.id().equals("megafauna")).findFirst().orElse(null);
        expect(source, "Alien Megafauna starts from any of rotten flesh, bone, spider eye or gunpowder", megafauna != null
            && megafauna.triggers().containsAll(List.of("minecraft:rotten_flesh", "minecraft:bone", "minecraft:spider_eye", "minecraft:gunpowder")),
            megafauna == null ? "no tree" : String.join(", ", megafauna.triggers()));
        Milestone bio = Milestones.get("mam_megafauna_bio_organic_properties");
        boolean bioBlocked = bio != null && Milestones.blocker(company(), bio) != null;
        boolean bioByBone = bio != null && Milestones.blocker(company("mam_megafauna_skeleton_research"), bio) == null;
        expect(source, "Bio-Organic Properties needs any one mob node", bioBlocked && bioByBone, "");
        Milestone ender = Milestones.get("mam_megafauna_ender_analysis");
        boolean enderBlocked = ender != null && Milestones.blocker(company("mam_megafauna_bio_organic_properties"), ender) == Milestones.get("mam_quartz_quartz_crystals");
        boolean enderOpen = ender != null && Milestones.blocker(company("mam_megafauna_bio_organic_properties", "mam_quartz_quartz_crystals"), ender) == null;
        expect(source, "Ender Analysis also needs Quartz Crystals, from the Quartz tree", enderBlocked && enderOpen, "");
        Milestone backpack = Milestones.get("mam_mycelia_elytra_backpack");
        expect(source, "the Elytra Backpack node needs Wingsuit Research and the Copper Backtank",
            backpack != null && backpack.needs().containsAll(List.of("mam_mycelia_wingsuit_research", "mam_megafauna_copper_backtank")), "");
        Company depot = company("mam_alien_depot_expansion_200", "mam_alien_depot_expansion_300", "mam_alien_upload_upgrade_30_min");
        expect(source, "the Depot research tiers: 3 stacks and 30 items/min", DepotBlockEntity.cloudStacks(depot) == 3 && DepotBlockEntity.uploadPerMinute(depot) == 30,
            DepotBlockEntity.cloudStacks(depot) + " stacks, " + DepotBlockEntity.uploadPerMinute(depot) + "/min");

        // ---- the gateway's ring: one part per phase, as the Space Elevator grows
        expect(source, "the Wormhole Gateway has five phases of parts", Templates.GATEWAY_MAX == 5 && Templates.gateway().maxLevel == 5
            && Templates.gateway().height() <= 48, Templates.gateway().width() + " x " + Templates.gateway().length() + " x " + Templates.gateway().height());

        report(source, "done: " + passed + " passed, " + failed + " failed");
        return failed == 0 ? 1 : 0;
    }
}
