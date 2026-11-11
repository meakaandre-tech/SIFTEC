package com.meakaandre.siftec.command;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.TestQueue;
import com.meakaandre.siftec.TestReport;
import com.meakaandre.siftec.building.BuildingTests;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.Locale;
import java.util.Set;

/**
 * Item frames and item displays with Create Fly's Vanillin as shipped (run by the smoke test), and the site the client
 * check looks at.
 * <ul>
 * <li>{@code /siftec selftest frames}: builds a white wall facing south with item frames, item displays and a loose
 * diamond in front of it. Prints "SELFTEST frames ..." and "... done: N passed, M failed".</li>
 * <li>{@code /siftec selftest frames client <check>}: asks every client to run one of its checks (see
 * FramesClientTests): {@code frames}, then {@code done}.</li>
 * <li>{@code /siftec worldtest frames view}: noon, and the player (in spectator) in front of the wall.</li>
 * </ul>
 */
public final class FramesTests {
    static final int X = 18040, Y = 200, Z = -18000;
    /** The white wall the frames hang on runs along x at this z; the targets stand {@link #FRAME_STEP} apart from its x. */
    public static final BlockPos FRAMES = new BlockPos(X, Y, Z);
    public static final int FRAME_STEP = 2, FRAME_HEIGHT = 2;
    /** What is on the wall, in order from FRAMES' x: the names the client logs. */
    public static final String[] FRAME_TARGETS = {"frame diamond", "frame steel ingot", "frame empty", "display diamond", "display steel ingot", "item entity diamond"};
    private static final String TAG = "siftec_frames";

    private static final TestQueue LATER = new TestQueue();
    private static final TestReport REPORT = new TestReport("SELFTEST frames ");

    /** Tells the client to run one of its checks. */
    public record Probe(String action) implements CustomPacketPayload {
        public static final Type<Probe> TYPE = new Type<>(Siftec.id("selftest_frames_probe"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Probe> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Probe::action, Probe::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private FramesTests() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(Probe.TYPE, Probe.STREAM_CODEC);
        ServerTickEvents.END_SERVER_TICK.register(server -> LATER.runDue(server.overworld().getGameTime()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LATER.clear());
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("frames").executes(FramesTests::run)
                    .then(Commands.literal("client").then(Commands.argument("check", StringArgumentType.word())
                        .executes(c -> probe(c.getSource(), StringArgumentType.getString(c, "check")))))))
                .then(Commands.literal("worldtest").then(Commands.literal("frames").then(Commands.literal("view").executes(c -> view(c.getSource())))))));
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

    private static int probe(CommandSourceStack source, String action) {
        for (ServerPlayer p : source.getServer().getPlayerList().getPlayers()) ServerPlayNetworking.send(p, new Probe(action));
        source.sendSuccess(() -> Component.literal("SELFTEST frames asked the clients: " + action), false);
        return 1;
    }

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        REPORT.reset();
        BuildingTests.platform(level, X - 10, Z - 10, X + 25, Z + 15, Y, 10);
        for (Entity e : level.getEntitiesOfClass(Entity.class, new AABB(X - 10, Y - 2, Z - 10, X + 25, Y + 10, Z + 15), e -> !(e instanceof ServerPlayer))) e.discard();
        REPORT.attempt(source, "frames site", () -> site(source, level));
        later(level, 10, () -> REPORT.report(source, "done: " + REPORT.totals()));
        return 1;
    }

    /**
     * A white wall facing south with, from its west end: an item frame holding a diamond, one holding a Steel Ingot, an
     * empty one, an item display of a diamond, one of a Steel Ingot, and a diamond lying loose (held in the air).
     */
    private static void site(CommandSourceStack source, ServerLevel level) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState white = Blocks.CONCRETE.white().defaultBlockState();
        int x0 = FRAMES.getX() - 3, x1 = FRAMES.getX() + FRAME_STEP * FRAME_TARGETS.length + 3;
        for (int x = x0; x <= x1; x++) {
            for (int y = Y; y <= Y + 6; y++) level.setBlock(pos.set(x, y, FRAMES.getZ()), white, Block.UPDATE_ALL);
            for (int z = FRAMES.getZ() + 1; z <= FRAMES.getZ() + 6; z++) level.setBlock(pos.set(x, Y - 1, z), white, Block.UPDATE_CLIENTS);
        }
        Item steel = item("siftec:steel_ingot");
        Item[] held = {Items.DIAMOND, steel, null};
        int frames = 0;
        for (int i = 0; i < 3; i++) {
            BlockPos at = new BlockPos(FRAMES.getX() + FRAME_STEP * i, Y + FRAME_HEIGHT, FRAMES.getZ() + 1);
            ItemFrame frame = new ItemFrame(level, at, Direction.SOUTH);
            if (held[i] != null) frame.setItem(new ItemStack(held[i]), false);
            frame.addTag(TAG);
            if (frame.survives() && level.addFreshEntity(frame)) frames++;
        }
        CommandSourceStack quiet = source.getServer().createCommandSourceStack().withSuppressedOutput().withLevel(level);
        Item[] shown = {Items.DIAMOND, steel};
        for (int i = 0; i < 2; i++) {
            double x = FRAMES.getX() + FRAME_STEP * (3 + i) + 0.5;
            source.getServer().getCommands().performPrefixedCommand(quiet, String.format(Locale.ROOT,
                "summon minecraft:item_display %.2f %.2f %.2f {item:{id:\"%s\",count:1},Tags:[\"%s\"],"
                    + "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[0.8f,0.8f,0.8f]}}",
                x, Y + FRAME_HEIGHT + 0.5, FRAMES.getZ() + 1.5, id(shown[i]), TAG));
        }
        // an item entity is drawn about a quarter block above where it is: level with the camera's eye at the frames
        ItemEntity loose = new ItemEntity(level, FRAMES.getX() + FRAME_STEP * 5 + 0.5, Y + FRAME_HEIGHT + 0.25, FRAMES.getZ() + 1.5, new ItemStack(Items.DIAMOND));
        loose.setNoGravity(true);
        loose.setDeltaMovement(0, 0, 0);
        loose.setNeverPickUp();
        loose.setUnlimitedLifetime();
        loose.addTag(TAG);
        level.addFreshEntity(loose);
        int made = frames;
        // the summons run once this command is done
        later(level, 5, () -> {
            int displays = level.getEntitiesOfClass(Display.ItemDisplay.class, new AABB(x0, Y, FRAMES.getZ(), x1, Y + 6, FRAMES.getZ() + 3)).size();
            REPORT.expect(source, "site: three item frames (two holding items), two item displays and a loose diamond in front of the white wall",
                made == 3 && displays == 2, made + " frames, " + displays + " displays");
        });
    }

    /** Noon, and the player (in spectator) in front of the wall, level with the frames. */
    private static int view(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        ServerLevel level = server.overworld();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set noon");
        double ex = FRAMES.getX() + 5.5, ey = Y + FRAME_HEIGHT + 0.5, ez = FRAMES.getZ() + 6.5;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) p.setGameMode(GameType.SPECTATOR);
            p.teleportTo(level, ex, ey - p.getEyeHeight(), ez, Set.of(), 180, 0, true);
        }
        source.sendSuccess(() -> Component.literal("SELFTEST frames view"), false);
        return 1;
    }
}
