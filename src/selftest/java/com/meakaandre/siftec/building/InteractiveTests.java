package com.meakaandre.siftec.building;

import com.google.gson.JsonElement;
import com.meakaandre.siftec.TestQueue;
import com.meakaandre.siftec.TestReport;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.drone.DronePortBlockEntity;
import com.meakaandre.siftec.hub.HubBlockEntity;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.power.PowerTests;
import com.meakaandre.siftec.prefab.Prefabs;
import com.meakaandre.siftec.prefab.Templates;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.registry.ModItems;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.AllItems;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import com.zurrtum.create.content.kinetics.crank.HandCrankBlockEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.antarcticgardens.cna.content.electricity.connector.AbstractElectricalConnector;
import org.antarcticgardens.cna.content.electricity.network.ElectricalNetwork;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The parts of a building a player is meant to click still work by hand, through the real right click
 * ({@code ServerPlayerGameMode.useItemOn}, the path a player's click takes, with Fabric's and Create's hooks), while the
 * rest of the building stays protected ({@code BuildingProtection.useRefused}, run by the smoke test).
 * <ul>
 * <li>{@code /siftec selftest interactive}: a survival player of the HUB's company turns the Craft Station's two hand
 * cranks (empty hand, and with an item in hand) and the Press and the Mixer spin; cannot break or wrench a crank (also
 * sneaking); empties a Craft Station Basin by hand; cannot encase its cogwheel; takes the HUB down with the Build Gun,
 * which removes the cranks and gives no crank back. Then a billboard's sign opens for writing, the Drone Port's terminal
 * takes a drone by hand, a wire is strung between two Power Poles by clicking their connectors, and a Constructor's Basin
 * is still not emptied by hand, and a refused click leaves nobody acting for what is placed next. Prints "SELFTEST interactive ..." and "... done: N passed, M failed".</li>
 * </ul>
 */
public final class InteractiveTests {
    static final int X = 21120, Y = 200, Z = -21000;
    static final String COMPANY = "selftest_interactive";
    static final UUID MEMBER = UUID.fromString("5e1f7e57-0000-4000-8000-00000000c4a1");

    private static final TestQueue LATER = new TestQueue();
    private static final TestReport REPORT = new TestReport("SELFTEST interactive ");

    private InteractiveTests() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> LATER.runDue(server.overworld().getGameTime()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LATER.clear());
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("interactive").executes(InteractiveTests::run)))));
    }

    private static void later(ServerLevel level, int ticks, Runnable run) {
        LATER.add(level.getGameTime() + ticks, () -> REPORT.attempt(level.getServer().createCommandSourceStack(), "a later check", run));
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        REPORT.expect(source, what, ok, detail);
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
    }

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        REPORT.reset();
        Company company = company(level);
        FakePlayer player = FakePlayer.get(level, new GameProfile(MEMBER, "selftest_interactive"));
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.setShiftKeyDown(false);
        BuildingTests.platform(level, X - 30, Z - 45, X + 30, Z + 45, Y, 32);
        BuildingTests.platform(level, X - 30, Z + 75, X + 40, Z + 140, Y, 30);
        REPORT.attempt(source, "the HUB's hand cranks", () -> hub(source, level, company, player));
        REPORT.attempt(source, "the other buildings' clickable parts", () -> others(source, level, company, player));
        later(level, 80, () -> REPORT.report(source, "done: " + REPORT.totals()));
        REPORT.report(source, "sites ready; the checks report within about four seconds");
        return 1;
    }

    /** The suite's company: the player is its only member, and it has every milestone and research done. */
    private static Company company(ServerLevel level) {
        CompanyData data = CompanyData.get(level.getServer());
        Company c = new Company();
        c.id = c.name = COMPANY;
        c.members.add(MEMBER.toString());
        c.owner = MEMBER.toString();
        var raw = Milestones.raw();
        for (String key : new String[]{"milestones", "milestones_mam"}) {
            if (raw == null || !raw.has(key)) continue;
            for (JsonElement m : raw.getAsJsonArray(key)) c.done.add(m.getAsJsonObject().get("id").getAsString());
        }
        data.companies().put(c.id, c);
        data.setDirty();
        return c;
    }

    /** A right click on a block, as the player's click reaches the server, with this in the main hand. */
    private static InteractionResult click(FakePlayer player, BlockPos pos, ItemStack held) {
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        return player.gameMode.useItemOn(player, player.level(), held, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    private static float speed(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof KineticBlockEntity k ? k.getSpeed() : 0;
    }

    private static BlockPos at(Prefabs.Spot spot, int[] cell) {
        return spot.at(cell[0], cell[1], cell[2]);
    }

    // ------------------------------------------------------------------------------------------------ the HUB

    private static void hub(CommandSourceStack source, ServerLevel level, Company company, FakePlayer player) {
        Prefabs.Spot spot = new Prefabs.Spot(Prefabs.Kind.HUB, new BlockPos(X, Y, Z), Direction.SOUTH);
        if (level.getBlockEntity(spot.terminal()) instanceof HubBlockEntity old) Prefabs.dismantle(level, old);
        Prefabs.Check placed = Prefabs.place(level, spot, company, null);
        HubBlockEntity hub = level.getBlockEntity(spot.terminal()) instanceof HubBlockEntity h ? h : null;
        if (!placed.ok() || hub == null) {
            expect(source, "HUB: it goes down for the test company", false, placed.message().getString());
            return;
        }
        BlockPos pressCrank = at(spot, Templates.PRESS_CRANK), mixerCrank = at(spot, Templates.MIXER_CRANK);
        BlockPos press = at(spot, Templates.PRESS), mixer = at(spot, Templates.MIXER), cog = at(spot, Templates.MIXER_COG);
        BlockPos basin = at(spot, Templates.PRESS_BASIN);
        // the player stands in the terminal hall, by the Craft Station
        BlockPos stand = spot.at(-4, 1, 6);
        player.setPos(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
        expect(source, "HUB: the cranks are its Craft Station's, kept in the building index", BuildingIndex.isMember(level, pressCrank)
            && BuildingIndex.isMember(level, mixerCrank) && level.getBlockEntity(pressCrank) instanceof HandCrankBlockEntity, String.valueOf(level.getBlockState(pressCrank)));
        expect(source, "HUB: the Press and the Mixer stand still before anyone cranks", speed(level, press) == 0 && speed(level, mixer) == 0,
            speed(level, press) + " / " + speed(level, mixer) + " RPM");

        // right click with an empty hand on the Press's crank, with an Iron Ingot in hand on the Mixer's
        InteractionResult onPress = click(player, pressCrank, ItemStack.EMPTY);
        InteractionResult onMixer = click(player, mixerCrank, new ItemStack(Items.IRON_INGOT));
        int pressUse = level.getBlockEntity(pressCrank) instanceof HandCrankBlockEntity c ? c.inUse : -1;
        int mixerUse = level.getBlockEntity(mixerCrank) instanceof HandCrankBlockEntity c ? c.inUse : -1;
        expect(source, "HUB: a survival player's right click turns the Press's hand crank, as Create's does", onPress.consumesAction() && pressUse > 0,
            onPress + ", in use " + pressUse);
        expect(source, "HUB: with an item in hand the right click still turns the Mixer's hand crank", onMixer.consumesAction() && mixerUse > 0,
            onMixer + ", in use " + mixerUse);
        later(level, 3, () -> {
            expect(source, "HUB: the cranked Press spins", speed(level, press) != 0, speed(level, press) + " RPM");
            expect(source, "HUB: the cranked Mixer spins (through its cogwheel)", speed(level, mixer) != 0 && speed(level, cog) != 0,
                "mixer " + speed(level, mixer) + ", cog " + speed(level, cog) + " RPM");
        });

        // still protected: not broken, not wrenched off or picked up, not sneak-wrenched
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        boolean broke = player.gameMode.destroyBlock(pressCrank);
        expect(source, "HUB: the same player cannot break a hand crank", !broke && level.getBlockEntity(pressCrank) instanceof HandCrankBlockEntity,
            "broke " + broke + ", now " + level.getBlockState(pressCrank));
        BlockState before = level.getBlockState(mixerCrank);
        click(player, mixerCrank, new ItemStack(AllItems.WRENCH));
        player.setShiftKeyDown(true);
        click(player, mixerCrank, new ItemStack(AllItems.WRENCH));
        player.setShiftKeyDown(false);
        int cranksHeld = player.getInventory().countItem(item("create:hand_crank"));
        expect(source, "HUB: a wrench neither turns nor picks up a hand crank, sneaking or not", level.getBlockState(mixerCrank) == before && cranksHeld == 0,
            "now " + level.getBlockState(mixerCrank) + ", cranks in inventory " + cranksHeld);

        // the rest of the Craft Station: its Basins are emptied by hand, its cogwheel takes no casing
        insert(level, basin, new ItemStack(item("create:iron_sheet"), 2));
        click(player, basin, ItemStack.EMPTY);
        int sheets = player.getInventory().countItem(item("create:iron_sheet"));
        expect(source, "HUB: an empty hand takes what a Craft Station Basin holds", sheets == 2, sheets + " plates taken");
        BlockState cogBefore = level.getBlockState(cog);
        ItemStack casing = new ItemStack(item("create:andesite_casing"));
        click(player, cog, casing);
        expect(source, "HUB: an Andesite Casing does not go onto the Craft Station's cogwheel", level.getBlockState(cog) == cogBefore && casing.getCount() == 1,
            "now " + level.getBlockState(cog) + ", casings left " + casing.getCount());

        // dismantled with the Build Gun, the HUB takes its cranks with it, and no crank comes back or drops
        later(level, 20, () -> {
            player.getInventory().clearContent();
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.BUILD_GUN.get()));
            boolean down = BuildGun.takeDownHub(player, hub, company);
            int back = player.getInventory().countItem(item("create:hand_crank"));
            int dropped = level.getEntitiesOfClass(ItemEntity.class, new AABB(spot.terminal()).inflate(40), e -> e.getItem().is(item("create:hand_crank"))).size();
            expect(source, "HUB: taken down with the Build Gun, its hand cranks go with it (none given back or dropped)",
                down && level.getBlockState(pressCrank).isAir() && level.getBlockState(mixerCrank).isAir() && !BuildingIndex.isMember(level, pressCrank)
                    && back == 0 && dropped == 0,
                "down " + down + ", press crank " + level.getBlockState(pressCrank) + ", mixer crank " + level.getBlockState(mixerCrank) + ", given " + back + ", dropped " + dropped);
            player.getInventory().clearContent();
        });
    }

    private static void insert(ServerLevel level, BlockPos pos, ItemStack stack) {
        Storage<ItemVariant> s = ItemStorage.SIDED.find(level, pos, Direction.UP);
        if (s == null) return;
        try (Transaction tx = Transaction.openOuter()) {
            s.insert(ItemVariant.of(stack), stack.getCount(), tx);
            tx.commit();
        }
    }

    // ------------------------------------------------------------------------------------------------ the others

    private static @Nullable ControllerBlockEntity place(CommandSourceStack source, ServerLevel level, @Nullable Company company, String id, BlockPos anchor) {
        BuildingDef def = Buildings.def(id);
        BuildingTemplate t = def == null ? null : BuildingTemplate.of(def);
        if (t == null) {
            expect(source, id + " exists", false, "");
            return null;
        }
        Placement p = new Placement(def, anchor, Direction.NORTH, false);
        Buildings.Check check = Buildings.place(level, p, company, null, true);
        ControllerBlockEntity c = level.getBlockEntity(p.at(t.controller)) instanceof ControllerBlockEntity ce ? ce : null;
        if (!check.ok() || c == null) {
            expect(source, id + " goes down at " + anchor.toShortString(), false, check.message().getString()
                + (check.blocked.isEmpty() ? "" : " first blocked " + check.blocked.getFirst().toShortString()));
        }
        return c;
    }

    private static void others(CommandSourceStack source, ServerLevel level, Company company, FakePlayer player) {
        // a billboard's sign: written on by hand, standing in front of it
        ControllerBlockEntity board = place(source, level, company, "billboard_small", new BlockPos(X - 20, Y, Z + 100));
        BlockPos sign = BuildingLogic.cell(board, ModBlocks.BILLBOARD_SMALL_SIGN.get());
        if (sign != null) {
            Direction front = level.getBlockState(sign).getValue(HorizontalDirectionalBlock.FACING);
            BlockPos stand = sign.relative(front, 2);
            player.setPos(stand.getX() + 0.5, sign.getY(), stand.getZ() + 0.5);
            InteractionResult r = click(player, sign, ItemStack.EMPTY);
            UUID editor = level.getBlockEntity(sign) instanceof SignBlockEntity s ? s.getPlayerWhoMayEdit() : null;
            expect(source, "billboard: a right click on its sign opens it for writing", BuildingIndex.isMember(level, sign) && MEMBER.equals(editor),
                r + ", editor " + editor);
            if (level.getBlockEntity(sign) instanceof SignBlockEntity s) s.setAllowedPlayerEditor(null);
        } else {
            expect(source, "billboard: it has its sign", false, board == null ? "not placed" : "no sign cell");
        }

        // the Drone Port's loading terminal takes a drone by hand
        ControllerBlockEntity dock = place(source, level, company, "drone_port", new BlockPos(X + 10, Y, Z + 110));
        if (dock != null && SpecialBuildings.droneTerminal(level, dock) instanceof DronePortBlockEntity port) {
            port.hasDrone = false;
            BlockPos terminal = port.getBlockPos();
            player.setPos(terminal.getX() + 0.5, terminal.getY() + 1, terminal.getZ() + 2.5);
            ItemStack drone = new ItemStack(ModItems.CARDBOARD_DRONE.get());
            InteractionResult r = click(player, terminal, drone);
            expect(source, "Drone Port: its terminal takes a Cardboard Drone by hand", port.hasDrone && drone.isEmpty(), r + ", has drone " + port.hasDrone);
        } else {
            expect(source, "Drone Port: it goes down with its terminal", false, dock == null ? "not placed" : "no terminal");
        }

        // a wire strung by hand between two Power Poles, clicking their connectors
        ControllerBlockEntity pa = PowerTests.build(level, source, "power_pole_mk1", new BlockPos(X - 20, Y, Z + 130), Direction.SOUTH);
        ControllerBlockEntity pb = PowerTests.build(level, source, "power_pole_mk1", new BlockPos(X - 12, Y, Z + 130), Direction.SOUTH);
        AbstractElectricalConnector ca = PowerTests.connector(level, pa), cb = PowerTests.connector(level, pb);
        if (ca != null && cb != null) {
            for (AbstractElectricalConnector c : List.of(ca, cb)) if (c.getNetwork() == null) c.setNetwork(new ElectricalNetwork(c));
            player.setPos(X - 16 + 0.5, Y, Z + 132.5);
            ItemStack wire = new ItemStack(item("create_new_age:copper_wire"), 4);
            InteractionResult first = click(player, ca.getBlockPos(), wire);
            InteractionResult second = click(player, cb.getBlockPos(), wire);
            expect(source, "Power Poles: a wire is strung between two poles by clicking their connectors", ca.isConnected(cb.getBlockPos()) && wire.getCount() < 4,
                first + " then " + second + ", wire left " + wire.getCount());
        } else {
            expect(source, "Power Poles: two poles go down with their connectors", false, pa + " " + pb);
        }
        for (ControllerBlockEntity c : new ControllerBlockEntity[]{pa, pb}) if (c != null) Buildings.dismantle(level, c);

        // a sealed building's own machines are still not used by hand: a Constructor's Basin keeps what it holds
        ControllerBlockEntity con = place(source, level, company, "constructor", new BlockPos(X + 30, Y, Z + 100));
        BlockPos basin = BuildingLogic.cell(con, block("create:basin"));
        if (basin != null) {
            player.getInventory().clearContent();
            insert(level, basin, new ItemStack(Items.IRON_INGOT, 3));
            player.setPos(basin.getX() + 0.5, Y, basin.getZ() + 3.5);
            InteractionResult r = click(player, basin, ItemStack.EMPTY);
            int taken = player.getInventory().countItem(Items.IRON_INGOT);
            expect(source, "Constructor: its Basin is not emptied by hand (a building is fed and emptied at its ports)", taken == 0, r + ", " + taken + " taken");
            // a click cut short (refused here, by Create's hook) leaves nobody acting: a block put down next is nobody's
            BlockPos chest = new BlockPos(X + 38, Y, Z + 130);
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            String owner = level.getBlockEntity(chest) == null ? "no chest" : level.getBlockEntity(chest).getAttached(Ownership.OWNER);
            expect(source, "after a refused click the player is no longer acting: a chest put down next has no owner", owner == null, "owner " + owner);
            level.setBlock(chest, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        } else {
            expect(source, "Constructor: it goes down with its Basin", false, con == null ? "not placed" : "no basin");
        }
        player.getInventory().clearContent();
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }
}
