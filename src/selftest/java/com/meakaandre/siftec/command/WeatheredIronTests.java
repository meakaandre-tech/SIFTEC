package com.meakaandre.siftec.command;

import com.meakaandre.siftec.TestQueue;
import com.meakaandre.siftec.TestReport;
import com.meakaandre.siftec.building.BuildingTests;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.owner.Ownership;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.AllBlocks;
import com.zurrtum.create.content.kinetics.base.DirectionalKineticBlock;
import com.zurrtum.create.content.kinetics.belt.transport.TransportedItemStack;
import com.zurrtum.create.content.kinetics.fan.processing.AllFanProcessingTypes;
import com.zurrtum.create.content.logistics.depot.DepotBlockEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Set;

/**
 * The Steel Casing, the steel window and its pane wash into Create's weathered iron (run by the smoke test).
 * <ul>
 * <li>{@code /siftec selftest weathered}: by Create's own splashing (what an Encased Fan blowing through water does to an
 * item), and on a Depot in front of a real Encased Fan blowing through water. Prints "SELFTEST weathered ..." and, about
 * twenty seconds later, "... done: N passed, M failed".</li>
 * <li>{@code /siftec worldtest weathered view}: noon, and the player (in spectator) looking at the fan and the Depot.</li>
 * </ul>
 */
public final class WeatheredIronTests {
    static final int Y = 200;
    /** The Depot the Encased Fan washes the steel on; fan and motor west of it, water on both sides. */
    static final BlockPos SPLASH = new BlockPos(18120, Y, -18000);

    private static final TestQueue LATER = new TestQueue();
    private static final TestReport REPORT = new TestReport("SELFTEST weathered ");

    private WeatheredIronTests() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> LATER.runDue(server.overworld().getGameTime()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LATER.clear());
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("weathered").executes(WeatheredIronTests::run)))
                .then(Commands.literal("worldtest").then(Commands.literal("weathered").then(Commands.literal("view").executes(c -> view(c.getSource())))))));
    }

    private static void later(ServerLevel level, int ticks, Runnable run) {
        LATER.add(level.getGameTime() + ticks, () -> REPORT.attempt(level.getServer().createCommandSourceStack(), "a later check", run));
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        REPORT.reset();
        BuildingTests.platform(level, SPLASH.getX() - 10, SPLASH.getZ() - 10, SPLASH.getX() + 10, SPLASH.getZ() + 10, Y, 8);
        REPORT.attempt(source, "weathered iron", () -> weathered(source, level));
        later(level, 460, () -> REPORT.report(source, "done: " + REPORT.totals()));
        REPORT.report(source, "site ready; the splash check reports in about twenty seconds");
        return 1;
    }

    private static void weathered(CommandSourceStack source, ServerLevel level) {
        String[][] pairs = {{"siftec:steel_casing", "create:weathered_iron_block"}, {"siftec:steel_window", "create:weathered_iron_window"},
            {"siftec:steel_window_pane", "create:weathered_iron_window_pane"}};
        for (String[] p : pairs) {
            ItemStack in = new ItemStack(item(p[0]));
            boolean can = AllFanProcessingTypes.SPLASHING.canProcess(in, level);
            List<ItemStack> out = can ? AllFanProcessingTypes.SPLASHING.process(in.copy(), level) : null;
            String got = out == null ? "nothing" : out.stream().map(s -> s.getCount() + " " + id(s.getItem())).toList().toString();
            REPORT.expect(source, "weathered: splashing " + p[0] + " makes " + p[1],
                out != null && out.size() == 1 && out.getFirst().is(item(p[1])) && out.getFirst().getCount() == 1, got);
        }
        // a real fan: creative motor, fan blowing east, water, the Depot, water again (a pulling fan washes it too); its
        // chunks forced, as no player is near to keep the fan and the Depot ticking
        forced(level, SPLASH, true);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = -4; x <= 3; x++)
            for (int z = -1; z <= 1; z++) {
                level.setBlock(pos.set(SPLASH.getX() + x, Y - 1, SPLASH.getZ() + z), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                if (z != 0) level.setBlock(pos.set(SPLASH.getX() + x, Y, SPLASH.getZ() + z), Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
            }
        level.setBlock(SPLASH.east(2), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(SPLASH.east(), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(SPLASH, AllBlocks.DEPOT.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(SPLASH.west(), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(SPLASH.west(2), AllBlocks.ENCASED_FAN.defaultBlockState().setValue(DirectionalKineticBlock.FACING, Direction.EAST), Block.UPDATE_ALL);
        level.setBlock(SPLASH.west(3), AllBlocks.CREATIVE_MOTOR.defaultBlockState().setValue(DirectionalKineticBlock.FACING, Direction.EAST), Block.UPDATE_ALL);
        // the fan and the Depot belong to a company that has every milestone (weathered iron is locked behind steel)
        CompanyData data = CompanyData.get(level.getServer());
        Company owner = new Company();
        owner.id = owner.name = "selftest_splash";
        for (Milestone m : Milestones.all()) owner.done.add(m.id());
        data.companies().put(owner.id, owner);
        data.setDirty();
        for (BlockPos at : new BlockPos[]{SPLASH, SPLASH.west(2), SPLASH.west(3)})
            if (level.getBlockEntity(at) instanceof BlockEntity be) be.setAttached(Ownership.OWNER, owner.id);
        if (level.getBlockEntity(SPLASH) instanceof DepotBlockEntity depot) {
            depot.depotBehaviour.setHeldItem(new TransportedItemStack(new ItemStack(item("siftec:steel_casing"))));
            depot.notifyUpdate();
        }
        later(level, 440, () -> {
            ItemStack now = level.getBlockEntity(SPLASH) instanceof DepotBlockEntity d ? d.depotBehaviour.getHeldItemStack() : ItemStack.EMPTY;
            REPORT.expect(source, "weathered: a Steel Casing on a Depot in front of an Encased Fan blowing through water turns into weathered iron",
                now.is(item("create:weathered_iron_block")), now.isEmpty() ? "the Depot is empty" : now.getCount() + " " + id(now.getItem()));
            forced(level, SPLASH, false);
        });
    }

    private static void forced(ServerLevel level, BlockPos o, boolean force) {
        for (int cx = (o.getX() - 24) >> 4; cx <= (o.getX() + 24) >> 4; cx++)
            for (int cz = (o.getZ() - 24) >> 4; cz <= (o.getZ() + 24) >> 4; cz++) {
                level.setChunkForced(cx, cz, force);
                if (force) level.getChunk(cx, cz);
            }
    }

    /** Noon, and the player (in spectator) south of the fan and the Depot, looking down at them. */
    private static int view(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        ServerLevel level = server.overworld();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set noon");
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) p.setGameMode(GameType.SPECTATOR);
            p.teleportTo(level, SPLASH.getX() - 0.5, Y + 1.6 - p.getEyeHeight(), SPLASH.getZ() + 3.2, Set.of(), 180, 22, true);
        }
        source.sendSuccess(() -> Component.literal("SELFTEST weathered view"), false);
        return 1;
    }
}
