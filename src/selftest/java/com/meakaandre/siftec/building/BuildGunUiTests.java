package com.meakaandre.siftec.building;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.TestReport;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.registry.ModItems;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Selftests for the Build Gun's Satisfactory-style controls ({@code /siftec selftest buildgun_ui}, lines "SELFTEST
 * buildgun-ui"): the build menu lists only what the company has unlocked, building and taking down go through the new
 * packets with the same costs and refunds, a mass dismantle refunds every building, and build hotbar bindings are kept
 * ({@code ... restart} checks them again after the restart, the smoke client's own binding too).
 * <p>
 * For the smoke client: {@code /siftec worldtest gunrow} puts three test buildings in a row in front of the client's
 * gun test floor, {@code worldtest gunlook <n>} turns every player to look at one of them, and {@code worldtest
 * buildmenu} asks the client to check its build menu ({@link MenuCheck}).
 */
public final class BuildGunUiTests {
    private static final TestReport REPORT = new TestReport("SELFTEST buildgun-ui ");
    private static final String COMPANY = "selftest_gun_ui";
    /** A building that costs something and is unlocked by an early milestone, and that milestone. */
    private static final String BUILDING = "constructor", UNLOCK = "hub_upgrade_3";
    /** Well away from the other selftests. */
    private static final int X = 6200, Y = 200, Z = 2600, SPACING = 12;
    /** The fake player's bars: a binding the restart must keep. */
    private static final int BAR = 2, SLOT = 4;
    private static final String BOUND = "smelter";
    /** The smoke client binds the Constructor to slot 3 of its first bar from the build menu (smoke-script.txt). */
    private static final int CLIENT_BAR = 0, CLIENT_SLOT = 2;
    /** The client's gun test floor (BuildingTests) and the row on it. */
    private static final int GX = 1100, GY = 181, GZ = 1000, ROW_Z = GZ - 10, ROW_STEP = 5;

    private BuildGunUiTests() {
    }

    /** Server to client: check the build menu's rules and layout and log the result ("SELFTEST buildgun-client"). */
    public record MenuCheck(int token) implements CustomPacketPayload {
        public static final Type<MenuCheck> TYPE = new Type<>(Siftec.id("build_menu_check"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MenuCheck> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> buf.writeVarInt(p.token), buf -> new MenuCheck(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(MenuCheck.TYPE, MenuCheck.STREAM_CODEC);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("buildgun_ui").executes(BuildGunUiTests::run)
                    .then(Commands.literal("restart").executes(BuildGunUiTests::restart))))
                .then(Commands.literal("worldtest")
                    .then(Commands.literal("gunrow").executes(BuildGunUiTests::row))
                    .then(Commands.literal("gunlook").then(Commands.argument("n", IntegerArgumentType.integer(0, 2)).executes(BuildGunUiTests::look)))
                    .then(Commands.literal("buildmenu").executes(BuildGunUiTests::menuCheck)))));
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        REPORT.expect(source, what, ok, detail);
    }

    private static Company company(ServerLevel level) {
        CompanyData data = CompanyData.get(level.getServer());
        Company c = new Company();
        c.id = COMPANY;
        c.name = "Selftest Gun UI";
        c.done.add(UNLOCK);
        data.companies().put(COMPANY, c);
        data.setDirty();
        return c;
    }

    private static void giveCost(FakePlayer fake, BuildingDef def, int times) {
        fake.getInventory().clearContent();
        fake.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.BUILD_GUN.get()));
        int slot = 1;
        for (Milestone.Cost cost : def.cost()) {
            if (!cost.present()) continue;
            int left = cost.count() * times;
            while (left > 0) {
                ItemStack stack = cost.icon().copyWithCount(Math.min(left, cost.icon().getMaxStackSize()));
                fake.getInventory().setItem(slot++, stack);
                left -= stack.getCount();
            }
        }
    }

    private static int costItems(FakePlayer fake, BuildingDef def) {
        int n = 0;
        for (Milestone.Cost cost : def.cost()) {
            if (!cost.present()) continue;
            for (int i = 0; i < fake.getInventory().getContainerSize(); i++) if (cost.matches(fake.getInventory().getItem(i))) n += fake.getInventory().getItem(i).getCount();
        }
        return n;
    }

    private static int costTotal(BuildingDef def) {
        int n = 0;
        for (Milestone.Cost cost : def.cost()) if (cost.present()) n += cost.count();
        return n;
    }

    private static Placement at(BuildingDef def, BuildingTemplate t, int k) {
        // facing south the box runs from anchor.x - maxX to anchor.x - minX, and anchor.z to anchor.z + depth - 1
        return new Placement(def, new BlockPos(X + k * SPACING + t.maxX, Y, Z), Direction.SOUTH, false);
    }

    private static @Nullable ControllerBlockEntity controllerOf(ServerLevel level, Placement p, BuildingTemplate t) {
        return level.getBlockEntity(p.at(t.controller)) instanceof ControllerBlockEntity c && BUILDING.equals(c.buildingId) ? c : null;
    }

    private static UUID uuid(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    private static UUID fakeId() {
        return uuid("siftec selftest build hotbars");
    }

    // ------------------------------------------------------------------------------------------------ the suite

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        REPORT.reset();
        REPORT.attempt(source, "menu", () -> menu(source, level));
        REPORT.attempt(source, "packets", () -> packets(source, level));
        REPORT.attempt(source, "hotbars", () -> hotbars(source, level));
        REPORT.report(source, "result: " + REPORT.totals());
        return 1;
    }

    /** Only unlocked buildings show in the build menu; dev buildings only in creative; the search filters what shows. */
    private static void menu(CommandSourceStack source, ServerLevel level) {
        Company fresh = new Company();
        fresh.id = "selftest_gun_ui_fresh";
        List<String> none = BuildMenu.unlocked(fresh, false);
        expect(source, "menu: a new company sees only what needs no unlock (the HUB)", none.contains("hub") && !none.contains(BUILDING)
            && !none.contains("test_building") && none.stream().allMatch(id -> Buildings.def(id) != null && Buildings.def(id).unlock().isEmpty()), none.toString());
        Company one = company(level);
        List<String> early = BuildMenu.unlocked(one, false);
        expect(source, "menu: HUB Upgrade 3 shows the Constructor and the Power Pole Mk.1, not the Miner Mk.1",
            early.contains(BUILDING) && early.contains("power_pole_mk1") && !early.contains("miner_mk1"), early.toString());
        List<String> creative = BuildMenu.unlocked(fresh, true);
        expect(source, "menu: a creative player sees every building, the test buildings too",
            creative.size() == Buildings.all().size() && creative.contains("test_building"), creative.size() + " of " + Buildings.all().size());
        List<String> shown = new ArrayList<>();
        for (BuildingDef def : BuildMenu.visible(early, "")) shown.add(def.id());
        expect(source, "menu: it lists exactly the unlocked buildings", Set.copyOf(shown).equals(Set.copyOf(early)), shown.toString());
        List<String> poles = new ArrayList<>();
        for (BuildingDef def : BuildMenu.visible(early, "pole")) poles.add(def.id());
        expect(source, "menu: the search 'pole' keeps only the unlocked Power Pole", poles.equals(List.of("power_pole_mk1")), poles.toString());
        List<String> unplaced = new ArrayList<>();
        for (BuildingDef def : Buildings.all()) if (BuildMenu.place(def).sub().equals("other")) unplaced.add(def.id());
        expect(source, "menu: every building has its Satisfactory category and subcategory", unplaced.isEmpty(), unplaced.toString());
    }

    /** Building and taking down through the Place and Dismantle packets: the same costs and refunds, every building of a mass dismantle. */
    private static void packets(CommandSourceStack source, ServerLevel level) {
        BuildingDef def = Buildings.def(BUILDING);
        BuildingTemplate t = def == null ? null : BuildingTemplate.of(def);
        if (def == null || t == null) {
            expect(source, "packets: the Constructor and its template exist", false, "");
            return;
        }
        Company company = company(level);
        BuildingTests.platform(level, X - 4, Z - 8, X + 3 * SPACING + 4, Z + t.depth + 4, Y, t.height + 2);
        FakePlayer gunner = FakePlayer.get(level);
        gunner.setGameMode(GameType.SURVIVAL);
        gunner.setPos(X + SPACING + 0.5, Y, Z - 4.5);
        List<Placement> spots = new ArrayList<>();
        for (int k = 0; k < 3; k++) spots.add(at(def, t, k));
        for (Placement p : spots) {
            ControllerBlockEntity old = controllerOf(level, p, t);
            if (old != null) Buildings.dismantle(level, old);
        }
        try {
            // one building, placed with a Place packet: its cost is taken from what the player carries
            giveCost(gunner, def, 1);
            int before = costItems(gunner, def);
            Placement p = spots.getFirst();
            BuildGun.place(gunner, new BuildGunPayloads.Place(BUILDING, p.anchor(), p.facing().get2DDataValue(), false), company);
            ControllerBlockEntity built = controllerOf(level, p, t);
            expect(source, "packets: a Place packet builds it and takes its cost", built != null && costItems(gunner, def) == before - costTotal(def),
                built == null ? "not built" : "cost items " + costItems(gunner, def) + " of " + before);
            // and a Dismantle packet naming one of its blocks takes it down with the full refund
            BlockPos block = p.at(t.members.getFirst());
            int down = BuildGun.dismantle(gunner, new BuildGunPayloads.Dismantle(List.of(block), false), company);
            expect(source, "packets: a Dismantle packet (held left click) takes it down with its whole cost back",
                down == 1 && controllerOf(level, p, t) == null && costItems(gunner, def) == before, down + " down, cost items " + costItems(gunner, def) + " of " + before);
            // a block out of the gun's reach is not taken down
            BuildGun.place(gunner, new BuildGunPayloads.Place(BUILDING, p.anchor(), p.facing().get2DDataValue(), false), company);
            gunner.setPos(X + SPACING + 0.5, Y, Z - 200.5);
            int far = BuildGun.dismantle(gunner, new BuildGunPayloads.Dismantle(List.of(block), false), company);
            gunner.setPos(X + SPACING + 0.5, Y, Z - 4.5);
            expect(source, "packets: a building out of the gun's reach is left standing", far == 0 && controllerOf(level, p, t) != null, far + " down");
            ControllerBlockEntity first = controllerOf(level, p, t);
            if (first != null) Buildings.dismantle(level, first);

            // three built, then one mass dismantle (a block of each, one of them twice): all three come down, each refunded
            giveCost(gunner, def, 3);
            int three = costItems(gunner, def);
            for (Placement s : spots) BuildGun.place(gunner, new BuildGunPayloads.Place(BUILDING, s.anchor(), s.facing().get2DDataValue(), false), company);
            int standing = 0;
            for (Placement s : spots) if (controllerOf(level, s, t) != null) standing++;
            int spent = three - costItems(gunner, def);
            List<BlockPos> targets = new ArrayList<>();
            for (Placement s : spots) targets.add(s.at(t.members.get(t.members.size() / 2)));
            targets.add(spots.getFirst().at(t.controller));
            int mass = BuildGun.dismantle(gunner, new BuildGunPayloads.Dismantle(targets, false), company);
            int left = 0;
            for (Placement s : spots) if (controllerOf(level, s, t) != null) left++;
            expect(source, "packets: a mass dismantle takes down all three (each once) and refunds every one",
                standing == 3 && spent == 3 * costTotal(def) && mass == 3 && left == 0 && costItems(gunner, def) == three,
                standing + " built for " + spent + ", " + mass + " down, " + left + " left, cost items " + costItems(gunner, def) + " of " + three);
        } finally {
            for (Placement s : spots) {
                ControllerBlockEntity c = controllerOf(level, s, t);
                if (c != null) Buildings.dismantle(level, c);
            }
            gunner.getInventory().clearContent();
        }
    }

    /** A new player's first bar starts with the Power Pole; a binding is kept, a wrong one refused. */
    private static void hotbars(CommandSourceStack source, ServerLevel level) {
        var server = level.getServer();
        BuildHotbars.Bars fresh = BuildHotbars.of(server, uuid("siftec selftest new player"));
        expect(source, "hotbars: a new player's first bar starts with the Power Pole Mk.1, the rest empty",
            fresh.get(0, 0).equals("power_pole_mk1") && fresh.slots.stream().filter(s -> !s.isEmpty()).count() == 1, fresh.slots.toString());
        boolean ok = BuildHotbars.bind(server, fakeId(), BAR, SLOT, BOUND);
        boolean wrong = BuildHotbars.bind(server, fakeId(), BAR, BuildHotbars.SLOTS, BOUND) || BuildHotbars.bind(server, fakeId(), BAR, 0, "no_such_building");
        expect(source, "hotbars: binding a slot keeps it, a slot or building that is not one is refused",
            ok && !wrong && BuildHotbars.of(server, fakeId()).get(BAR, SLOT).equals(BOUND), "bound " + ok + ", wrong " + wrong);
        // the packet codec carries all hundred slots
        BuildHotbars.Bars bars = BuildHotbars.of(server, fakeId());
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        BuildGunPayloads.Hotbars.STREAM_CODEC.encode(buf, new BuildGunPayloads.Hotbars(bars.page, bars.slots));
        BuildGunPayloads.Hotbars back = BuildGunPayloads.Hotbars.STREAM_CODEC.decode(buf);
        expect(source, "hotbars: the hotbar packet carries all ten bars", back.slots().equals(bars.slots) && back.slots().size() == BuildHotbars.BARS * BuildHotbars.SLOTS,
            back.slots().size() + " slots");
    }

    /** After the restart: the fake player's binding, and the smoke client's own (from its build menu), are still there. */
    private static int restart(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        var server = source.getServer();
        REPORT.reset();
        expect(source, "restart: a bound slot is kept over a restart", BuildHotbars.has(server, fakeId()) && BuildHotbars.of(server, fakeId()).get(BAR, SLOT).equals(BOUND),
            BuildHotbars.has(server, fakeId()) ? BuildHotbars.of(server, fakeId()).get(BAR, SLOT) : "no bars");
        String found = "";
        for (Map.Entry<String, BuildHotbars.Bars> e : BuildHotbars.all(server).entrySet()) {
            if (e.getKey().equals(fakeId().toString())) continue;
            BuildHotbars.Bars b = e.getValue();
            if (b.slots.size() > CLIENT_BAR * BuildHotbars.SLOTS + CLIENT_SLOT && BUILDING.equals(b.slots.get(CLIENT_BAR * BuildHotbars.SLOTS + CLIENT_SLOT))) found = e.getKey();
        }
        expect(source, "restart: the smoke client's Constructor in slot 3 of its first bar (bound in the build menu) is kept", !found.isEmpty(),
            BuildHotbars.all(server).size() + " players with bars");
        REPORT.report(source, "restart result: " + REPORT.totals());
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ for the smoke client

    private static Placement rowSpot(BuildingDef def, int n) {
        return new Placement(def, new BlockPos(GX + (n - 1) * ROW_STEP, GY, ROW_Z), Direction.NORTH, false);
    }

    /** Three test buildings in a row on the client's gun test floor, for its mass dismantle. */
    private static int row(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        BuildingDef def = Buildings.def("test_building");
        int built = 0;
        if (def != null) {
            for (int n = 0; n < 3; n++) if (Buildings.place(level, rowSpot(def, n), null, null, true).ok()) built++;
        }
        REPORT.report(source, "gun row " + built + " built");
        return 1;
    }

    /** Turns every player, standing on the gun test floor, to look at one building of the row. */
    private static int look(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        BuildingDef def = Buildings.def("test_building");
        BuildingTemplate t = def == null ? null : BuildingTemplate.of(def);
        if (t == null) return 0;
        AABB box = rowSpot(def, IntegerArgumentType.getInteger(context, "n")).bounds(t);
        Vec3 to = box.getCenter();
        for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
            Vec3 feet = new Vec3(GX + 0.5, GY, GZ + 0.5), eye = feet.add(0, player.getEyeHeight(), 0);
            double dx = to.x - eye.x, dy = to.y - eye.y, dz = to.z - eye.z;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            player.teleportTo(level, feet.x, feet.y, feet.z, Set.of(), yaw, pitch, true);
        }
        REPORT.report(source, "gun look " + IntegerArgumentType.getInteger(context, "n"));
        return 1;
    }

    /** Asks every client to check its build menu. */
    private static int menuCheck(CommandContext<CommandSourceStack> context) {
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) ServerPlayNetworking.send(player, new MenuCheck(1));
        REPORT.report(context.getSource(), "build menu check sent");
        return 1;
    }
}
