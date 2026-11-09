package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.TestQueue;
import com.meakaandre.siftec.TestReport;
import com.meakaandre.siftec.building.BuildingLogic;
import com.meakaandre.siftec.building.BuildingTests;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.PortBlock;
import com.meakaandre.siftec.building.SimAccess;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.AllBlocks;
import com.zurrtum.create.AllItems;
import com.zurrtum.create.content.kinetics.base.DirectionalKineticBlock;
import com.zurrtum.create.content.kinetics.belt.BeltBlock;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.content.kinetics.belt.transport.TransportedItemStack;
import com.zurrtum.create.content.kinetics.fan.processing.AllFanProcessingTypes;
import com.zurrtum.create.content.logistics.depot.DepotBlockEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The belt polish's selftests (run by the smoke test), and the sites the client checks look at.
 * <ul>
 * <li>{@code /siftec selftest beltpolish}: builds the client's sites (a vertical belt with a reference block in a white
 * room, item frames and item displays on a white wall, a sloped belt for each belt Mk) and checks that the Steel Casing,
 * the steel window and its pane wash into Create's weathered iron (by Create's own splashing, and on a Depot in front of a
 * real Encased Fan blowing through water). Prints "SELFTEST beltpolish ..." and "... done: N passed, M failed".</li>
 * <li>{@code /siftec selftest beltpolish client <check>}: asks every client to run one of its checks (see
 * BeltPolishClientTests): {@code vertical}, {@code sloped}, {@code frames}, {@code ponder}, then {@code done}.</li>
 * <li>{@code /siftec selftest lowbelts sim}: the loaded-against-simulated count. The same line twice (a Storage Container
 * putting 150 iron ingots onto a belt, a built-in andesite tunnel splitting them onto two side belts, built-in funnels at
 * the three ends feeding chests): one kept loaded, the other let go so the simulation runs it. Five in-game minutes after
 * both start, each output must hold the same count in both, and again once the second is loaded and caught up. Prints
 * "SELFTEST lowbelts-sim ..." and "... done: N passed, M failed".</li>
 * <li>{@code /siftec worldtest beltpolish view <name>}: noon, and the player at one of the screenshot spots;
 * {@code worldtest beltpolish ponder <scene> <time> <item>} and {@code ponder close}: Create's Ponder scene of an item,
 * open on the client at a moment of one of its scenes.</li>
 * </ul>
 */
public final class BeltPolishTests {
    static final int X = 18000, Y = 200, Z = -18000;
    /** The vertical belt's foot (four blocks up), and the reference block (one block, black) two above its top. */
    public static final BlockPos VERTICAL = new BlockPos(X, Y, Z), VERTICAL_REF = VERTICAL.above(5);
    /** The white wall the frames hang on runs along x at this z; the targets stand {@link #FRAME_STEP} apart from its x. */
    public static final BlockPos FRAMES = new BlockPos(X + 40, Y, Z);
    public static final int FRAME_STEP = 2, FRAME_HEIGHT = 2;
    /** What is on the wall, in order from FRAMES' x: the names the client logs. */
    public static final String[] FRAME_TARGETS = {"frame diamond", "frame steel ingot", "frame empty", "display diamond", "display steel ingot", "item entity diamond"};
    /** The sloped belts: one for each belt Mk, three blocks up and along, rising east, three blocks apart in z. */
    public static final BlockPos SLOPES = new BlockPos(X + 80, Y, Z);
    public static final int SLOPE_RUN = 3, SLOPE_GAP = 3;
    /** The lowbelts suite's Mk.1 to Mk.6 belts: six blocks long, east from here, two blocks apart in z. */
    public static final BlockPos MK_ROW = new BlockPos(LowBeltTests.X + 70, LowBeltTests.Y, LowBeltTests.Z + 20);
    public static final int MK_LENGTH = 6, MK_GAP = 2;
    /** The first of the four placing lines (andesite tunnel, brass tunnel, andesite funnel, brass funnel; three apart in z), each attachment two blocks in. */
    public static final BlockPos PLACING = new BlockPos(LowBeltTests.X, LowBeltTests.Y, LowBeltTests.Z);
    /** The Depot the Encased Fan washes the steel on; fan and motor west of it, water on both sides. */
    static final BlockPos SPLASH = new BlockPos(X + 120, Y, Z);
    /** The loaded-against-simulated twins: A here, B {@link #SIM_B} blocks south. */
    static final int SIM_X = -19000, SIM_Z = 19000, SIM_B = 600, SIM_ITEMS = 150, SIM_MINUTES = 5;
    private static final String TAG = "siftec_beltpolish";

    private static final TestQueue LATER = new TestQueue();
    private static final TestReport REPORT = new TestReport("SELFTEST beltpolish ");
    private static final TestReport SIM = new TestReport("SELFTEST lowbelts-sim ");

    /** Tells the client to run one of its checks, or (for the screenshots) to open or close a Ponder scene. */
    public record Probe(String action, String arg) implements CustomPacketPayload {
        public static final Type<Probe> TYPE = new Type<>(Siftec.id("selftest_beltpolish_probe"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Probe> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, Probe::action, ByteBufCodecs.STRING_UTF8, Probe::arg, Probe::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private BeltPolishTests() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(Probe.TYPE, Probe.STREAM_CODEC);
        ServerTickEvents.END_SERVER_TICK.register(server -> LATER.runDue(server.overworld().getGameTime()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LATER.clear());
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest")
                    .then(Commands.literal("beltpolish").executes(BeltPolishTests::run)
                        .then(Commands.literal("client").then(Commands.argument("check", StringArgumentType.word())
                            .executes(c -> probe(c.getSource(), StringArgumentType.getString(c, "check"), "")))))
                    .then(Commands.literal("lowbelts").then(Commands.literal("sim").executes(BeltPolishTests::simStart))))
                .then(Commands.literal("worldtest").then(Commands.literal("beltpolish")
                    .then(Commands.literal("view").then(Commands.argument("name", StringArgumentType.word())
                        .executes(c -> view(c.getSource(), StringArgumentType.getString(c, "name")))))
                    .then(Commands.literal("ponder")
                        .then(Commands.literal("close").executes(c -> probe(c.getSource(), "ponderclose", "")))
                        .then(Commands.argument("scene", IntegerArgumentType.integer(0))
                            .then(Commands.argument("time", IntegerArgumentType.integer(0))
                                .then(Commands.argument("item", StringArgumentType.greedyString()).executes(c -> probe(c.getSource(), "ponderopen",
                                    StringArgumentType.getString(c, "item") + " " + IntegerArgumentType.getInteger(c, "scene") + " "
                                        + IntegerArgumentType.getInteger(c, "time")))))))))));
    }

    private static void later(ServerLevel level, int ticks, Runnable run) {
        LATER.add(level.getGameTime() + ticks, () -> REPORT.attempt(level.getServer().createCommandSourceStack(), "a later check", run));
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
    }

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static int probe(CommandSourceStack source, String action, String arg) {
        for (ServerPlayer p : source.getServer().getPlayerList().getPlayers()) ServerPlayNetworking.send(p, new Probe(action, arg));
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("SELFTEST beltpolish asked the clients: " + action + " " + arg), false);
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ the sites

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        REPORT.reset();
        BuildingTests.platform(level, X - 20, Z - 20, X + 140, Z + 30, Y, 14);
        for (Entity e : level.getEntitiesOfClass(Entity.class, new AABB(X - 20, Y - 2, Z - 20, X + 140, Y + 14, Z + 30), e -> !(e instanceof ServerPlayer))) e.discard();
        REPORT.attempt(source, "vertical site", () -> verticalSite(source, level));
        REPORT.attempt(source, "frames site", () -> framesSite(source, level));
        REPORT.attempt(source, "sloped site", () -> slopedSite(source, level));
        REPORT.attempt(source, "weathered iron", () -> weathered(source, level));
        later(level, 460, () -> REPORT.report(source, "done: " + REPORT.totals()));
        REPORT.report(source, "sites ready; the splash check reports in about twenty seconds");
        return 1;
    }

    /** A white room (floor and walls) with a vertical belt four blocks high in the middle and a black block above it. */
    private static void verticalSite(CommandSourceStack source, ServerLevel level) {
        whiteRoom(level, VERTICAL, 10, 12);
        String company = LowBeltTests.company(level, "selftest_beltpolish_vertical", LowBeltTests.beltTokens().getFirst(), false);
        LowBeltTests.belt(level, VERTICAL, VERTICAL.above(3), company);
        level.setBlock(VERTICAL_REF, Blocks.CONCRETE.black().defaultBlockState(), Block.UPDATE_ALL);
        BlockState belt = level.getBlockState(VERTICAL);
        REPORT.expect(source, "site: a vertical belt stands in the white room", belt.is(AllBlocks.BELT)
            && belt.getValue(BeltBlock.SLOPE) == com.zurrtum.create.content.kinetics.belt.BeltSlope.VERTICAL, belt.toString());
    }

    private static void whiteRoom(ServerLevel level, BlockPos middle, int half, int height) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState white = Blocks.CONCRETE.white().defaultBlockState();
        for (int x = -half; x <= half; x++)
            for (int z = -half; z <= half; z++) {
                level.setBlock(pos.set(middle.getX() + x, middle.getY() - 1, middle.getZ() + z), white, Block.UPDATE_CLIENTS);
                if (Math.abs(x) == half || Math.abs(z) == half)
                    for (int y = 0; y < height; y++) level.setBlock(pos.set(middle.getX() + x, middle.getY() + y, middle.getZ() + z), white, Block.UPDATE_CLIENTS);
            }
    }

    /**
     * A white wall facing south with, from its west end: an item frame holding a diamond, one holding a Steel Ingot, an
     * empty one, an item display of a diamond, one of a Steel Ingot, and a diamond lying loose (held in the air).
     */
    private static void framesSite(CommandSourceStack source, ServerLevel level) {
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

    /** One sloped belt for each belt Mk, owned by a company with that Mk, rising three blocks east. */
    private static void slopedSite(CommandSourceStack source, ServerLevel level) {
        List<String> tokens = LowBeltTests.beltTokens();
        int sloped = 0;
        for (int i = 0; i < tokens.size(); i++) {
            String company = LowBeltTests.company(level, "selftest_lowbelts_mk" + (i + 1), tokens.get(i), false);
            BlockPos a = SLOPES.south(SLOPE_GAP * i), b = a.offset(SLOPE_RUN, SLOPE_RUN, 0);
            LowBeltTests.belt(level, a, b, company);
            BlockState mid = level.getBlockState(a.offset(1, 1, 0));
            if (mid.is(AllBlocks.BELT) && mid.getValue(BeltBlock.SLOPE) != com.zurrtum.create.content.kinetics.belt.BeltSlope.HORIZONTAL) sloped++;
        }
        REPORT.expect(source, "site: a sloped belt for each belt Mk", sloped == tokens.size() && sloped == 6, sloped + " of " + tokens.size());
    }

    // ------------------------------------------------------------------------------------------------ weathered iron

    /**
     * The Steel Casing, the steel window and its pane wash into weathered iron: by Create's own splashing (what an Encased
     * Fan blowing through water does to an item), and on a Depot in front of a real fan blowing through water.
     */
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

    // ------------------------------------------------------------------------------------------------ screenshot spots

    /** The player (in spectator, for a low eye) at a screenshot spot at noon: the eye's position, yaw and pitch. */
    private static int view(CommandSourceStack source, String name) {
        MinecraftServer server = source.getServer();
        ServerLevel level = server.overworld();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set noon");
        int lx = LowBeltTests.X, lz = LowBeltTests.Z;
        // the placing lines (andesite tunnel, brass tunnel, andesite funnel, brass funnel at x+2, three apart in z): the eye
        // just above the 5 px rails, two blocks south of the line, straight at the attachment
        double[] eye = switch (name) {
            case "andesite_tunnel" -> new double[]{lx + 2.5, Y + 0.6, lz + 2.4, 180, 8};
            case "brass_tunnel" -> new double[]{lx + 2.5, Y + 0.6, lz + 5.4, 180, 8};
            case "andesite_funnel" -> new double[]{lx + 2.5, Y + 0.6, lz + 8.4, 180, 8};
            case "brass_funnel" -> new double[]{lx + 2.5, Y + 0.6, lz + 11.4, 180, 8};
            // the same spot, the crosshair off the funnel (to the right of it, over the belt)
            case "brass_funnel_aside" -> new double[]{lx + 2.5, Y + 0.6, lz + 11.4, 160, 8};
            // the Basin line under its ceiling: from the south, at its funnel and the Basin, its tunnel to the left
            case "basin" -> new double[]{lx + 6.0, Y + 0.55, lz + 42.6, 160, 6};
            // the vertical belt from the side (the client's own check looks the same way)
            case "vertical" -> verticalEye(level.getBlockState(VERTICAL));
            case "sloped" -> new double[]{SLOPES.getX() + 2.0, Y + 1.6, SLOPES.getZ() + 2.4, 180, 12};
            // the Mk.1 to Mk.6 belts (x+70..75, z+20..30 in steps of two), low from their south-west corner
            case "mkrow" -> new double[]{lx + 68.2, Y + 0.6, lz + 32.0, 205, 8};
            case "frames" -> new double[]{FRAMES.getX() + 5.5, Y + FRAME_HEIGHT + 0.5, FRAMES.getZ() + 6.5, 180, 0};
            case "splash" -> new double[]{SPLASH.getX() - 0.5, Y + 1.6, SPLASH.getZ() + 3.2, 180, 22};
            // the loaded twin of the loaded-against-simulated count: above its three chests, looking down at the line
            case "simline" -> simEye();
            default -> null;
        };
        if (eye == null) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("no view " + name));
            return 0;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) p.setGameMode(GameType.SPECTATOR);
            p.teleportTo(level, eye[0], eye[1] - p.getEyeHeight(), eye[2], Set.of(), (float) eye[3], (float) eye[4], true);
        }
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("SELFTEST beltpolish view " + name), false);
        return 1;
    }

    /**
     * The side view of the vertical belt (eye x, y, z, yaw, pitch), as the client's check and the screenshot take it: six
     * blocks off, looking along the axis its back face does not lie on, so the belt's depth runs across the picture.
     */
    public static double[] verticalEye(BlockState belt) {
        Direction back = belt.hasProperty(BeltBlock.HORIZONTAL_FACING) ? belt.getValue(BeltBlock.HORIZONTAL_FACING) : Direction.NORTH;
        double x = VERTICAL.getX() + 0.5, z = VERTICAL.getZ() + 0.5;
        // yaw 0 looks south (+z), -90 east (+x)
        return back.getAxis() == Direction.Axis.Z ? new double[]{x - 6, Y + 2.5, z, -90, 0} : new double[]{x, Y + 2.5, z - 6, 0, 0};
    }

    // ------------------------------------------------------------------------------------------------ loaded against simulated

    /** Above the loaded twin's line, between its three chests, looking down at it. */
    private static double[] simEye() {
        if (TWINS.isEmpty() || TWINS.getFirst().chests().size() < 3) return new double[]{SIM_X + 0.5, Y + 6, SIM_Z + 8, 180, 40};
        double x = 0, z = 0;
        for (BlockPos c : TWINS.getFirst().chests()) {
            x += c.getX() + 0.5;
            z += c.getZ() + 0.5;
        }
        return new double[]{x / 3, Y + 7, z / 3 + 5, 180, 50};
    }

    /** One twin: its Storage Container and the three chests at the ends of its line (straight on, left, right). */
    private record Twin(String name, BlockPos origin, @Nullable ControllerBlockEntity source, List<BlockPos> chests) {
    }

    private static final List<Twin> TWINS = new ArrayList<>();

    private static int simStart(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        SIM.reset();
        TWINS.clear();
        String company = LowBeltTests.company(level, "selftest_lowbelts_sim", LowBeltTests.beltTokens().get(1), false);
        for (int t = 0; t < 2; t++) {
            BlockPos o = new BlockPos(SIM_X, Y, SIM_Z + SIM_B * t);
            BuildingTests.platform(level, o.getX() - 24, o.getZ() - 24, o.getX() + 24, o.getZ() + 24, Y, 10);
            TWINS.add(simLine(source, level, t == 0 ? "A (loaded)" : "B (simulated)", o, company));
        }
        SIM.report(source, "twins built; B is let go in a few seconds");
        long began = level.getGameTime();
        // the belts set themselves up, then the simulation reads both lines while they are loaded
        LATER.add(began + 60, () -> SIM.attempt(source, "recording", () -> {
            for (Twin tw : TWINS) if (tw.source() != null) Factories.recordNow(level, tw.source());
            SimData data = SimData.get(source.getServer());
            for (Twin tw : TWINS) {
                List<String> ends = new ArrayList<>();
                if (tw.source() != null) for (SimData.Link l : data.stored.links) if (l.from.equals(SimData.key(level, tw.source().getBlockPos())))
                    ends.add(String.format(Locale.ROOT, "%s x%.3f", BlockPos.of(l.toPort).subtract(tw.origin()).toShortString(), l.share));
                SIM.expect(source, "twin " + tw.name() + ": the simulation traces its line to the three chests", ends.size() == 3, ends.toString());
            }
            // twin B is let go: its chunks stop ticking and unload, and nothing loads them again
            Twin b = TWINS.get(1);
            forced(level, b.origin(), false);
        }));
        // once B is simulated, both start at the same tick: 150 iron ingots into A's container in the world and into B's in
        // the simulation
        LATER.when(began + 80, () -> simulated(level, TWINS.get(1)), 1200, ok -> SIM.attempt(source, "start", () -> {
            SIM.expect(source, "twin B: its container and chests are simulated once its chunks unload", ok, simDescribe(level, TWINS.get(1)));
            Factories.join();
            SimData data = SimData.get(source.getServer());
            Twin a = TWINS.get(0), b = TWINS.get(1);
            long put = a.source() == null ? 0 : fill(level, a.source(), SIM_ITEMS);
            SimData.Node bn = b.source() == null ? null : data.node(level, b.source().getBlockPos());
            if (bn != null) {
                Sim.add(bn.out, Keys.of(Items.IRON_INGOT), SIM_ITEMS);
                data.setDirty();
            }
            SIM.expect(source, "both twins get " + SIM_ITEMS + " iron ingots at the same tick", put == SIM_ITEMS && bn != null,
                "A " + put + ", B " + (bn == null ? "no simulated container" : "in the simulation"));
            long start = level.getGameTime();
            SIM.report(source, "both running from " + start + "; counted in " + SIM_MINUTES + " in-game minutes");
            LATER.add(start + SIM_MINUTES * 1200L, () -> SIM.attempt(source, "count", () -> count(source, level)));
        }));
        return 1;
    }

    /** One line: the container, a belt from its output port with a built-in andesite tunnel and two side belts, built-in funnels into chests. */
    private static Twin simLine(CommandSourceStack source, ServerLevel level, String name, BlockPos o, String company) {
        ControllerBlockEntity c = AlwaysTests.put(level, source, "storage_container", o, Direction.EAST);
        List<BlockPos> chests = new ArrayList<>();
        SimAccess.Port out = null;
        if (c != null) for (SimAccess.Port p : SimAccess.ports(c)) if (p.kind() == PortBlock.Kind.ITEM_OUT) out = p;
        if (c == null || out == null) {
            SIM.expect(source, "twin " + name + ": its Storage Container goes down with an item output", false, c == null ? "not placed" : "no item output port");
            return new Twin(name, o, c, chests);
        }
        Direction f = out.facing(), left = f.getCounterClockWise(), right = f.getClockWise();
        BlockPos s = out.pos().relative(f), end = s.relative(f, 7), t = s.relative(f, 3);
        BlockPos[] ends = {end, t.relative(left, 3), t.relative(right, 3)};
        Direction[] along = {f, left, right};
        for (int i = 0; i < 3; i++) {
            chests.add(ends[i].relative(along[i]));
            level.setBlock(ends[i].relative(along[i]), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        }
        LowBeltTests.belt(level, s, end, company);
        LowBeltTests.belt(level, t.relative(left), ends[1], company);
        LowBeltTests.belt(level, t.relative(right), ends[2], company);
        LowBeltTests.click(level, LowBeltTests.tester(level, GameType.CREATIVE, new ItemStack(AllItems.ANDESITE_TUNNEL)), t, Direction.UP);
        for (int i = 0; i < 3; i++)
            LowBeltTests.click(level, LowBeltTests.tester(level, GameType.CREATIVE, new ItemStack(AllItems.ANDESITE_FUNNEL)), ends[i], along[i].getOpposite());
        BlockState tunnel = LowBeltTests.Probe.attached(level, t);
        int funnels = 0;
        for (BlockPos e : ends) if (LowBeltTests.Probe.attached(level, e) != null) funnels++;
        SIM.expect(source, "twin " + name + ": a built-in andesite tunnel and three built-in funnels on its line",
            tunnel != null && tunnel.is(AllBlocks.ANDESITE_TUNNEL) && funnels == 3, (tunnel == null ? "no tunnel" : tunnel.toString()) + ", " + funnels + " funnels");
        return new Twin(name, o, c, chests);
    }

    private static void forced(ServerLevel level, BlockPos o, boolean force) {
        for (int cx = (o.getX() - 24) >> 4; cx <= (o.getX() + 24) >> 4; cx++)
            for (int cz = (o.getZ() - 24) >> 4; cz <= (o.getZ() + 24) >> 4; cz++) {
                level.setChunkForced(cx, cz, force);
                if (force) level.getChunk(cx, cz);
            }
    }

    private static boolean simulated(ServerLevel level, Twin t) {
        SimData data = SimData.get(level.getServer());
        if (t.source() == null) return false;
        SimData.Node s = data.node(level, t.source().getBlockPos());
        if (s == null || !s.sim) return false;
        for (BlockPos c : t.chests()) {
            SimData.Node n = data.node(level, c);
            if (n == null || !n.sim) return false;
        }
        return true;
    }

    private static String simDescribe(ServerLevel level, Twin t) {
        SimData data = SimData.get(level.getServer());
        StringBuilder sb = new StringBuilder();
        if (t.source() != null) {
            SimData.Node s = data.node(level, t.source().getBlockPos());
            sb.append("container ").append(s == null ? "not in the simulation" : (s.sim ? "simulated" : "ticking") + " out " + s.out);
        }
        for (BlockPos c : t.chests()) {
            SimData.Node n = data.node(level, c);
            sb.append("; chest ").append(c.subtract(t.origin()).toShortString()).append(' ').append(n == null ? "not in the simulation" : (n.sim ? "simulated" : "ticking") + " out " + n.out);
        }
        return sb.toString();
    }

    /** Puts this many iron ingots into the container's vaults; what went in. */
    private static long fill(ServerLevel level, ControllerBlockEntity c, int n) {
        long left = n;
        for (BlockPos pos : BuildingLogic.cells(c, block("create:item_vault"))) {
            Storage<ItemVariant> s = ItemStorage.SIDED.find(level, pos, null);
            if (s == null || left <= 0) continue;
            try (Transaction tx = Transaction.openOuter()) {
                left -= s.insert(ItemVariant.of(Items.IRON_INGOT), left, tx);
                tx.commit();
            }
        }
        return n - left;
    }

    /** The three outputs of a twin read from the world (chests), or from the simulation; plus what is left on the way. */
    private static long[] worldCounts(ServerLevel level, Twin t) {
        long[] out = new long[4];
        for (int i = 0; i < 3; i++) out[i] = LowBeltTests.stored(level, t.chests().get(i), Items.IRON_INGOT);
        long left = t.source() == null ? 0 : AlwaysTests.held(level, t.source(), Items.IRON_INGOT);
        out[3] = left;
        return out;
    }

    private static long[] simCounts(ServerLevel level, Twin t) {
        SimData data = SimData.get(level.getServer());
        String key = Keys.of(Items.IRON_INGOT);
        long[] out = new long[4];
        for (int i = 0; i < 3; i++) {
            SimData.Node n = data.node(level, t.chests().get(i));
            out[i] = n == null ? -1 : Sim.get(n.out, key);
        }
        long left = 0;
        if (t.source() != null) {
            SimData.Node s = data.node(level, t.source().getBlockPos());
            if (s != null) left += Sim.get(s.out, key);
            for (SimData.Link l : data.stored.links)
                if (s != null && l.from.equals(s.key())) for (SimData.Transit tr : l.transit) if (tr.key.equals(key)) left += tr.n;
        }
        out[3] = left;
        return out;
    }

    private static String counts(long[] c) {
        return "straight on " + c[0] + ", left " + c[1] + ", right " + c[2] + ", not there yet " + c[3];
    }

    /** Five minutes on: A from the world against B from the simulation; then B loaded again and caught up. */
    private static void count(CommandSourceStack source, ServerLevel level) {
        Factories.join();
        Twin a = TWINS.get(0), b = TWINS.get(1);
        long[] ca = worldCounts(level, a), cb = simCounts(level, b);
        SIM.report(source, "after " + SIM_MINUTES + " minutes: A (loaded) " + counts(ca) + "; B (simulated) " + counts(cb));
        SIM.expect(source, "the loaded line delivered all " + SIM_ITEMS + " ingots, split evenly by its built-in tunnel",
            ca[0] + ca[1] + ca[2] == SIM_ITEMS && ca[3] == 0 && ca[1] > 0 && ca[2] > 0, counts(ca));
        SIM.expect(source, "after " + SIM_MINUTES + " minutes each output of the simulated line holds what the loaded line's does",
            ca[0] == cb[0] && ca[1] == cb[1] && ca[2] == cb[2] && ca[3] == cb[3], "loaded " + counts(ca) + "; simulated " + counts(cb));
        SIM.expect(source, "twin B stayed simulated the whole time", simulated(level, b), simDescribe(level, b));
        forced(level, b.origin(), true);
        LATER.when(level.getGameTime(), () -> b.source() != null && !simulatedAny(level, b), 600, back -> SIM.attempt(source, "loaded again", () -> {
            long[] wb = worldCounts(level, b);
            SIM.expect(source, "loaded again: twin B's chests hold what the simulation gave them, the same as twin A's",
                back && wb[0] == ca[0] && wb[1] == ca[1] && wb[2] == ca[2], "A " + counts(ca) + "; B " + counts(wb) + (back ? "" : "; still simulated: " + simDescribe(level, b)));
            SIM.report(source, "done: " + SIM.totals());
        }));
    }

    private static boolean simulatedAny(ServerLevel level, Twin t) {
        SimData data = SimData.get(level.getServer());
        if (t.source() != null) {
            SimData.Node s = data.node(level, t.source().getBlockPos());
            if (s != null && s.sim) return true;
        }
        for (BlockPos c : t.chests()) {
            SimData.Node n = data.node(level, c);
            if (n != null && n.sim) return true;
        }
        return false;
    }
}
