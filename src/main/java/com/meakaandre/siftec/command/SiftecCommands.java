package com.meakaandre.siftec.command;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.block.MinerBlockEntity;
import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.block.PortableMinerBlockEntity;
import com.meakaandre.siftec.node.NodePlacer;
import com.meakaandre.siftec.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import com.meakaandre.siftec.node.Node;
import com.meakaandre.siftec.node.NodeMap;
import com.meakaandre.siftec.node.NodeType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/** Operator commands for testing: {@code /siftec node find <type>} and {@code /siftec node tp <type>}. */
public final class SiftecCommands {
    private SiftecCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("node")
                    .then(Commands.literal("find").then(type().executes(context -> find(context, false))))
                    .then(Commands.literal("tp").then(type().executes(context -> find(context, true)))))
                .then(Commands.literal("selftest")
                    .then(Commands.literal("setup").executes(context -> selfTest(context, true)))
                    .then(Commands.literal("check").executes(context -> selfTest(context, false)))
                    .then(Commands.literal("hub").executes(SiftecCommands::hubTest))
                    .then(Commands.literal("nether").executes(SiftecCommands::netherTest)))
        ));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> type() {
        return Commands.argument("type", StringArgumentType.word()).suggests((context, builder) ->
            SharedSuggestionProvider.suggest(Arrays.stream(NodeType.values()).map(NodeType::id), builder));
    }

    /**
     * Used by the automated test: puts a Portable Miner on the nearest iron node and a Miner Mk.1 with a
     * creative motor on the nearest copper node, then (check) reports what they made.
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
                if (state.getBlock() instanceof NodeBlock && state.getValue(NodeBlock.CORE)) break;
                pos.move(0, -1, 0);
            }
            if (pos.getY() <= level.getMinY()) {
                report(source, "SELFTEST " + type.id() + ": node " + node + " has no core block");
                continue;
            }
            BlockPos miner = pos.above().immutable();
            if (type == NodeType.IRON) powerTest(source, level, miner.above(8), setup);
            if (type == NodeType.IRON) ownerTest(source, level, miner.above(16), setup);
            if (setup) {
                if (type == NodeType.IRON) {
                    level.setBlockAndUpdate(miner, ModBlocks.PORTABLE_MINER.get().defaultBlockState());
                } else {
                    level.setBlockAndUpdate(miner, ModBlocks.MINER_MK1.get().defaultBlockState());
                    BlockState motor = BuiltInRegistries.BLOCK.getValue(Identifier.parse("create:creative_motor")).defaultBlockState();
                    if (motor.hasProperty(BlockStateProperties.FACING)) motor = motor.setValue(BlockStateProperties.FACING, Direction.DOWN);
                    level.setBlockAndUpdate(miner.above(), motor);
                }
                report(source, "SELFTEST setup " + node + " core at " + pos.toShortString());
            } else {
                BlockEntity be = level.getBlockEntity(miner);
                String made = be instanceof MinerBlockEntity m ? m.output.get() + " speed " + m.getSpeed()
                    : be instanceof PortableMinerBlockEntity m ? m.output.get().toString() : "no block entity (" + level.getBlockState(miner) + ")";
                report(source, "SELFTEST check " + node + ": " + made);
            }
        }
        return 1;
    }

    /** Used by the automated test: checks the milestone tables and runs one company through Tier 0. */
    private static int hubTest(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int count = 0;
        java.util.Set<String> missing = new java.util.TreeSet<>();
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
        Milestone coal = Milestones.get("coal_power");
        report(source, "SELFTEST hub: two members pay x" + company.costMultiplier() + "; tier 1 open " + Milestones.tierOpen(company, 1)
            + "; tier 3 open " + Milestones.tierOpen(company, 3) + "; coal power needs " + Milestones.blocker(company, coal).id()
            + "; backpack rewards " + company.count("backpack") + "; scanner copper " + company.hasToken("scanner:copper")
            + "; speed cap " + company.best("cap:", 32));
        int ours = 0;
        boolean steelGone = true;
        for (var holder : source.getServer().getRecipeManager().getRecipes()) {
            Identifier rid = holder.id().identifier();
            if (rid.getNamespace().equals("siftec")) ours++;
            if (Milestones.removedRecipes().contains(rid)) steelGone = false;
        }
        report(source, "SELFTEST recipes: " + ours + " SIFTEC recipes loaded; removed recipes gone " + steelGone
            + "; fluids " + com.meakaandre.siftec.fluid.ModFluids.ALL.keySet());
        report(source, "SELFTEST food: " + com.meakaandre.siftec.food.Food.describe(source.getLevel(), net.minecraft.world.item.Items.GOLDEN_CARROT)
            + "; " + com.meakaandre.siftec.food.Food.describe(source.getLevel(), net.minecraft.world.item.Items.PUMPKIN_PIE)
            + "; " + com.meakaandre.siftec.food.Food.describe(source.getLevel(), net.minecraft.world.item.Items.BREAD));
        report(source, "SELFTEST alternates: " + com.meakaandre.siftec.hub.Alternates.total() + " in the pool; a company that has only done Tier 0 could be offered "
            + com.meakaandre.siftec.hub.Alternates.eligible(company));
        report(source, "SELFTEST hub: Create's speed limit now reads " + com.zurrtum.create.infrastructure.config.AllConfigs.server().kinetics.maxRotationSpeed.get());
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
        int y = com.meakaandre.siftec.node.NodeSavedData.get(source.getServer()).height(node.key());
        report(source, "SELFTEST nether: " + node + " height " + y + " block "
            + (y == com.meakaandre.siftec.node.NodeSavedData.NO_HEIGHT ? "not placed" : level.getBlockState(new BlockPos(node.x(), y, node.z())).toString()));
        return 1;
    }

    /** A creative motor under one pole, a Power Line to a second pole, and a Power Storage under that one. */
    private static void powerTest(CommandSourceStack source, ServerLevel level, BlockPos at, boolean setup) {
        BlockPos poleA = at.above(), poleB = poleA.east(5), store = poleB.below();
        if (setup) {
            BlockState motor = BuiltInRegistries.BLOCK.getValue(Identifier.parse("create:creative_motor")).defaultBlockState();
            if (motor.hasProperty(BlockStateProperties.FACING)) motor = motor.setValue(BlockStateProperties.FACING, Direction.UP);
            level.setBlockAndUpdate(at, motor);
            level.setBlockAndUpdate(poleA, ModBlocks.POWER_POLE.get().defaultBlockState());
            level.setBlockAndUpdate(poleB, ModBlocks.POWER_POLE.get().defaultBlockState());
            level.setBlockAndUpdate(store, ModBlocks.POWER_STORAGE.get().defaultBlockState());
            if (level.getBlockEntity(poleA) instanceof com.meakaandre.siftec.power.PoleBlockEntity a
                && level.getBlockEntity(poleB) instanceof com.meakaandre.siftec.power.PoleBlockEntity b) {
                a.link(poleB);
                b.link(poleA);
            }
            return;
        }
        String a = level.getBlockEntity(poleA) instanceof com.meakaandre.siftec.power.PoleBlockEntity p ? "speed " + p.getSpeed() + " lines " + p.lines.size() : "missing";
        String b = level.getBlockEntity(poleB) instanceof com.meakaandre.siftec.power.PoleBlockEntity p ? "speed " + p.getSpeed() + " lines " + p.lines.size() : "missing";
        String st = level.getBlockEntity(store) instanceof com.meakaandre.siftec.power.StorageBlockEntity e ? "speed " + e.getSpeed() + " stored " + e.stored + " mode " + e.mode : "missing";
        report(source, "SELFTEST power: pole A " + a + "; pole B " + b + "; storage " + st);
    }

    /**
     * Used by the automated test. Two columns, each a creative motor over a Speed Governor over a shaft. In the
     * first everything belongs to a company whose limit is 64 RPM; in the second the shaft belongs to a company
     * still at 32, so the governor feeding it 64 should break.
     */
    private static void ownerTest(CommandSourceStack source, ServerLevel level, BlockPos at, boolean setup) {
        var data = com.meakaandre.siftec.company.CompanyData.get(source.getServer());
        BlockPos[] governors = {at, at.east(3)};
        if (setup) {
            Company fast = new Company(), slow = new Company();
            fast.id = "selftest_fast";
            slow.id = "selftest_slow";
            for (Milestone m : Milestones.all()) if (m.tokens().contains("cap:64")) fast.done.add(m.id());
            data.companies().put(fast.id, fast);
            data.companies().put(slow.id, slow);
            BlockState motor = BuiltInRegistries.BLOCK.getValue(Identifier.parse("create:creative_motor")).defaultBlockState();
            if (motor.hasProperty(BlockStateProperties.FACING)) motor = motor.setValue(BlockStateProperties.FACING, Direction.DOWN);
            BlockState shaft = BuiltInRegistries.BLOCK.getValue(Identifier.parse("create:shaft")).defaultBlockState();
            if (shaft.hasProperty(BlockStateProperties.AXIS)) shaft = shaft.setValue(BlockStateProperties.AXIS, Direction.Axis.Y);
            StringBuilder said = new StringBuilder();
            for (int i = 0; i < 2; i++) {
                BlockPos pos = governors[i];
                level.setBlockAndUpdate(pos.above(), motor);
                level.setBlockAndUpdate(pos, ModBlocks.SPEED_GOVERNOR.get().defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
                level.setBlockAndUpdate(pos.below(), shaft);
                BlockEntity below = level.getBlockEntity(pos.below());
                if (below != null) below.setAttached(com.meakaandre.siftec.owner.Ownership.OWNER, i == 0 ? fast.id : slow.id);
                if (level.getBlockEntity(pos) instanceof com.meakaandre.siftec.governor.GovernorBlockEntity governor) {
                    governor.setAttached(com.meakaandre.siftec.owner.Ownership.OWNER, fast.id);
                    governor.setTarget(48);
                    said.append(" set 48 -> ").append(governor.target());
                    governor.setTarget(200);
                    said.append(", set 200 -> ").append(governor.target()).append(" (limit ").append(governor.limit()).append(");");
                }
            }
            report(source, "SELFTEST owner setup:" + said);
            return;
        }
        for (int i = 0; i < 2; i++) {
            BlockPos pos = governors[i];
            String shaft = level.getBlockEntity(pos.below()) instanceof com.zurrtum.create.content.kinetics.base.KineticBlockEntity k
                ? "shaft speed " + k.getSpeed() + " owner " + k.getAttached(com.meakaandre.siftec.owner.Ownership.OWNER) : "shaft missing";
            report(source, "SELFTEST owner " + (i == 0 ? "same company" : "slower company below") + ": governor is "
                + BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()) + "; " + shaft);
        }
        // a locked recipe: refused for the company with nothing, allowed once the milestone is done
        BlockEntity machine = level.getBlockEntity(governors[0].below());
        Company fast = data.byId("selftest_fast");
        for (var holder : source.getServer().getRecipeManager().getRecipes()) {
            String lock = com.meakaandre.siftec.owner.RecipeLocks.lockOf(source.getServer(), holder.value());
            if (lock == null || machine == null || fast == null) continue;
            boolean before = com.meakaandre.siftec.owner.RecipeLocks.blocked(machine, holder.value());
            fast.done.add(lock);
            boolean after = com.meakaandre.siftec.owner.RecipeLocks.blocked(machine, holder.value());
            fast.done.remove(lock);
            report(source, "SELFTEST owner recipes: " + com.meakaandre.siftec.owner.RecipeLocks.count(source.getServer()) + " locked; "
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
        Optional<Node> found = NodeMap.nearest(level, from.x, from.z, type, 64);
        if (found.isEmpty()) {
            source.sendFailure(Component.translatable("siftec.scanner.none", Component.translatable(type.key())));
            return 0;
        }
        Node node = found.get();
        int distance = (int) Math.round(node.distanceTo(from.x, from.z));
        source.sendSuccess(() -> Component.translatable(
            "siftec.command.found", NodeBlock.label(type, found), node.x(), node.z(), distance
        ), false);
        if (teleport && source.getEntity() instanceof ServerPlayer player) {
            // loading the chunk places the node; stand next to it, above the ground
            level.getChunk(node.x() >> 4, node.z() >> 4);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, node.x() + 2, node.z()) + 2;
            player.teleportTo(level, node.x() + 2.5, y, node.z() + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
        }
        return 1;
    }
}
