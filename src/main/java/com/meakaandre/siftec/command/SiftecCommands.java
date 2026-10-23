package com.meakaandre.siftec.command;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.building.BuildingTests;
import com.meakaandre.siftec.building.BuildingsATests;
import com.meakaandre.siftec.building.BuildingsBTests;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.fluid.ModFluids;
import com.meakaandre.siftec.hub.Alternates;
import com.meakaandre.siftec.hub.HubMenu;
import com.meakaandre.siftec.hub.HubView;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.map.MapTests;
import com.meakaandre.siftec.node.Node;
import com.meakaandre.siftec.node.NodeMap;
import com.meakaandre.siftec.node.NodePlacer;
import com.meakaandre.siftec.node.NodeSavedData;
import com.meakaandre.siftec.node.NodeSites;
import com.meakaandre.siftec.node.NodeType;
import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.owner.RecipeLocks;
import com.meakaandre.siftec.power.PowerTests;
import com.meakaandre.siftec.prefab.PrefabTests;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.tweak.SelfMovingBelts;
import com.meakaandre.siftec.vehicle.VehicleTests;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.infrastructure.config.AllConfigs;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Operator commands for testing: {@code /siftec node find <type>} and {@code /siftec node tp <type>}. */
public final class SiftecCommands {
    private SiftecCommands() {
    }

    public static void register() {
        WorldTests.register();
        PrefabTests.register();
        BuildingTests.register();
        BuildingsATests.register();
        BuildingsBTests.register();
        AutomateTests.register();
        PowerTests.register();
        NodeTests.register();
        LogisticsTests.register();
        ProgressionTests.register();
        ExplorationTests.register();
        FoodTests.register();
        VehicleTests.register();
        MapTests.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("node")
                    .then(Commands.literal("find").then(type().executes(context -> find(context, false))))
                    .then(Commands.literal("tp").then(type().executes(context -> find(context, true)))))
                .then(Commands.literal("selftest")
                    .then(Commands.literal("setup").executes(context -> selfTest(context, true)))
                    .then(Commands.literal("check").executes(context -> selfTest(context, false)))
                    .then(Commands.literal("hub").executes(SiftecCommands::hubTest))
                    .then(Commands.literal("content").executes(ContentSelfTest::run))
                    .then(Commands.literal("nether").executes(SiftecCommands::netherTest)))
        ));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> type() {
        return Commands.argument("type", StringArgumentType.word()).suggests((context, builder) ->
            SharedSuggestionProvider.suggest(Arrays.stream(NodeType.values()).map(NodeType::id), builder));
    }

    /**
     * Used by the automated test: puts a Portable Miner on the nearest iron and copper nodes (in place of their
     * clump's middle), then (check) reports what they made.
     */
    private static int selfTest(CommandContext<CommandSourceStack> context, boolean setup) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        NodeMap.settle(level);
        for (NodeType type : new NodeType[]{NodeType.IRON, NodeType.COPPER}) {
            Optional<Node> found = NodeMap.nearest(level, 0, 0, type, 64);
            if (found.isEmpty()) {
                report(source, "SELFTEST " + type.id() + ": no node found");
                continue;
            }
            Node node = found.get();
            level.setChunkForced(node.x() >> 4, node.z() >> 4, true);
            level.getChunk(node.x() >> 4, node.z() >> 4);
            NodePlacer.placeChunk(level, node.x() >> 4, node.z() >> 4);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(node.x(), level.getMaxY(), node.z());
            while (pos.getY() > level.getMinY()) {
                BlockState state = level.getBlockState(pos);
                if (state.getBlock() instanceof NodeBlock || NodeSites.isSite(level, pos)) break;
                pos.move(0, -1, 0);
            }
            if (pos.getY() <= level.getMinY()) {
                report(source, "SELFTEST " + type.id() + ": node " + node + " has no core block");
                continue;
            }
            BlockPos miner = pos.above().immutable();
            if (type == NodeType.IRON) ownerTest(source, level, miner.above(16), setup);
            // the Build Gun's miners are tested by /siftec selftest buildings_a
            if (setup) {
                report(source, "SELFTEST setup " + node + " core at " + pos.toShortString());
            } else {
                var site = NodeSites.at(level, pos);
                report(source, "SELFTEST check " + node + ": core still " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock())
                    + (site == null ? ", NOT a node any more" : ", a node (" + site.look.id() + " " + site.purity.getSerializedName() + ")"));
                // the setup kept this chunk loaded until now; let it go
                level.setChunkForced(node.x() >> 4, node.z() >> 4, false);
            }
        }
        return 1;
    }

    /** Used by the automated test: checks the milestone tables and runs one company through Tier 0. */
    private static int hubTest(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int count = 0;
        Set<String> missing = new TreeSet<>();
        for (Milestone m : Milestones.all()) {
            count++;
            for (Milestone.Cost cost : m.cost()) {
                if (!cost.present()) missing.add(cost.key());
            }
            for (Identifier item : m.items()) {
                if (BuiltInRegistries.ITEM.getOptional(item).isEmpty()) missing.add(item.toString());
            }
        }
        report(source, "SELFTEST hub: " + count + " milestones and phases, " + Milestones.partIds().size() + " parts");
        report(source, "SELFTEST hub: ids not present in this install: " + missing);
        Company company = new Company();
        company.members.add("a");
        company.members.add("b");
        for (Milestone m : Milestones.tier(0)) {
            if (Milestones.blocker(company, m) != null) report(source, "SELFTEST hub: " + m.id() + " blocked, which is wrong");
            for (Milestone.Cost cost : m.cost()) company.pay(m, cost, company.needed(m, cost));
            if (!company.fullyPaid(m)) report(source, "SELFTEST hub: " + m.id() + " not paid, which is wrong");
            company.done.add(m.id());
        }
        // the HUB screen: button ids are bounds-checked, and the view survives the trip to the client
        boolean buttons = HubMenu.milestone(HubMenu.button(HubMenu.PAY, 0, 0)) == Milestones.tier(0).get(0)
            && HubMenu.milestone(HubMenu.button(HubMenu.PAY, HubMenu.PHASES, Milestones.phases().size() - 1)) == Milestones.phases().getLast()
            && HubMenu.milestone(HubMenu.button(HubMenu.PAY, 0, 63)) == null
            && HubMenu.milestone(HubMenu.button(HubMenu.PAY, 11, 0)) == null
            && HubMenu.milestone(-7) == null && HubMenu.action(-7) == 0
            && HubMenu.action(HubMenu.button(HubMenu.SIFT, 0, 0)) == HubMenu.SIFT;
        Milestone base = Milestones.tier(1).get(0);
        company.pay(base, base.cost().get(0), 7);
        HubView sent = HubView.of(company, false, 40);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        sent.write(buf);
        HubView got = HubView.read(buf);
        buf.release();
        report(source, "SELFTEST hub: screen buttons checked " + buttons + "; view paid " + got.paid(base, 0) + " of " + got.need(base.cost().get(0))
            + " (server " + company.paid(base, base.cost().get(0)) + " of " + company.cost(base.cost().get(0)) + "), done " + got.done.size()
            + ", tier 1 open " + (Milestones.blocker(got.asCompany(), base) == null) + ", lock " + got.lock);
        company.paid.clear();
        Milestone coal = Milestones.get("coal_power");
        report(source, "SELFTEST hub: two members pay x" + company.costMultiplier() + "; tier 1 open " + Milestones.tierOpen(company, 1)
            + "; tier 3 open " + Milestones.tierOpen(company, 3) + "; coal power needs " + Milestones.blocker(company, coal).id()
            + "; scanner copper " + company.hasToken("scanner:copper")
            + "; self-moving belts " + SelfMovingBelts.itemsPerMinute(company) + " items/min");
        int ours = 0;
        boolean steelGone = true;
        for (var holder : source.getServer().getRecipeManager().getRecipes()) {
            Identifier rid = holder.id().identifier();
            if (rid.getNamespace().equals("siftec")) ours++;
            if (Milestones.removedRecipes().contains(rid)) steelGone = false;
        }
        report(source, "SELFTEST recipes: " + ours + " SIFTEC recipes loaded; removed recipes gone " + steelGone
            + "; fluids " + ModFluids.ALL.keySet());
        report(source, "SELFTEST alternates: " + Alternates.total() + " in the pool; a company that has only done Tier 0 could be offered "
            + Alternates.eligible(company));
        report(source, "SELFTEST hub: Create's speed limit now reads " + AllConfigs.server().kinetics.maxRotationSpeed.get());
        return 1;
    }

    /** Used by the automated test: reports the block at the middle of the nearest quartz node. */
    private static int netherTest(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        Optional<Node> found = NodeMap.nearest(level, 150, 150, NodeType.QUARTZ, 8);
        if (found.isEmpty()) {
            report(source, "SELFTEST nether: no quartz node");
            return 0;
        }
        Node node = found.get();
        level.getChunk(node.x() >> 4, node.z() >> 4);
        NodePlacer.placeChunk(level, node.x() >> 4, node.z() >> 4);
        int y = NodeSavedData.get(source.getServer()).height(node.key());
        report(source, "SELFTEST nether: " + node + " height " + y + " block "
            + (y == NodeSavedData.NO_HEIGHT ? "not placed" : level.getBlockState(new BlockPos(node.x(), y, node.z())) + " under "
            + level.getBlockState(new BlockPos(node.x(), y + 1, node.z()))));
        return 1;
    }

    /**
     * Used by the automated test: a shaft that belongs to a company, for the recipe lock check (refused for the
     * company with nothing, allowed once the milestone is done).
     */
    private static void ownerTest(CommandSourceStack source, ServerLevel level, BlockPos at, boolean setup) {
        var data = CompanyData.get(source.getServer());
        if (setup) {
            Company owner = new Company();
            owner.id = "selftest_owner";
            data.companies().put(owner.id, owner);
            BlockState shaft = BuiltInRegistries.BLOCK.getValue(Identifier.parse("create:shaft")).defaultBlockState();
            if (shaft.hasProperty(BlockStateProperties.AXIS)) shaft = shaft.setValue(BlockStateProperties.AXIS, Direction.Axis.Y);
            level.setBlockAndUpdate(at, shaft);
            BlockEntity placed = level.getBlockEntity(at);
            if (placed != null) placed.setAttached(Ownership.OWNER, owner.id);
            report(source, "SELFTEST owner setup: shaft of " + owner.id + " at " + at.toShortString());
            return;
        }
        // a locked recipe: refused for the company with nothing, allowed once the milestone is done
        BlockEntity machine = level.getBlockEntity(at);
        Company fast = data.byId("selftest_owner");
        for (var holder : source.getServer().getRecipeManager().getRecipes()) {
            String lock = RecipeLocks.lockOf(source.getServer(), holder.value());
            if (lock == null || machine == null || fast == null) continue;
            boolean before = RecipeLocks.blocked(machine, holder.value());
            fast.done.add(lock);
            boolean after = RecipeLocks.blocked(machine, holder.value());
            fast.done.remove(lock);
            report(source, "SELFTEST owner recipes: " + RecipeLocks.count(source.getServer()) + " locked; "
                + holder.id().identifier() + " needs " + lock + "; blocked before " + before + ", after unlocking " + after);
            break;
        }
    }

    private static void report(CommandSourceStack source, String text) {
        Siftec.LOGGER.info(text);
        source.sendSuccess(() -> Component.literal(text), false);
    }

    private static int find(CommandContext<CommandSourceStack> context, boolean teleport) {
        CommandSourceStack source = context.getSource();
        NodeType type = NodeType.byId(StringArgumentType.getString(context, "type"));
        if (type == null) {
            source.sendFailure(Component.translatable("siftec.command.unknown_type"));
            return 0;
        }
        ServerLevel level = source.getLevel();
        Vec3 from = source.getPosition();
        // searched on the node map's worker thread; the answer comes when it is done
        NodeMap.nearestAsync(level, from.x, from.z, type, 64, found -> {
            if (found.isEmpty()) {
                source.sendFailure(Component.translatable("siftec.scanner.none", Component.translatable(type.key())));
                return;
            }
            Node node = found.get();
            int distance = (int) Math.round(node.distanceTo(from.x, from.z));
            source.sendSuccess(() -> Component.translatable(
                "siftec.command.found", NodeBlock.label(type, found), node.x(), node.z(), distance
            ), false);
            if (teleport && source.getEntity() instanceof ServerPlayer player) {
                // loading the chunk places the node; stand next to it, on the ground
                level.getChunk(node.x() >> 4, node.z() >> 4);
                for (int[] spot : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {0, 0}}) {
                    int x = node.x() + spot[0], z = node.z() + spot[1];
                    int y = node.y() != Node.SURFACE ? caveFloor(level, x, node.y(), z) : level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                    if (y <= level.getMinY() + 1) continue;
                    player.teleportTo(level, x + 0.5, y + (node.y() != Node.SURFACE ? 1 : 0), z + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
                    return;
                }
                // no ground anywhere next to it: do not drop the player into the void
                source.sendFailure(Component.literal("No ground next to that node to stand on"));
            }
        });
        return 1;
    }

    /** The floor near a cave height with room to stand above it, or the world's bottom if there is none. */
    private static int caveFloor(ServerLevel level, int x, int around, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int d = 0; d <= 32; d++) {
            for (int y : new int[]{around - d, around + d}) {
                if (!level.getBlockState(pos.set(x, y, z)).isAir() && level.getBlockState(pos.set(x, y + 1, z)).isAir() && level.getBlockState(pos.set(x, y + 2, z)).isAir()) return y;
            }
        }
        return level.getMinY();
    }
}
