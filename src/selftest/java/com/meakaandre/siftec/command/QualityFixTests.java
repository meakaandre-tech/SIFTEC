package com.meakaandre.siftec.command;

import com.meakaandre.siftec.DelayedChecks;
import com.meakaandre.siftec.TestQueue;
import com.meakaandre.siftec.TestReport;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.depot.DepotMenu;
import com.meakaandre.siftec.hub.Locks;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.prefab.Prefabs;
import com.meakaandre.siftec.vehicle.VehicleEntity;
import com.meakaandre.siftec.vehicle.VehicleKind;
import com.meakaandre.siftec.vehicle.Vehicles;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.UUID;

/**
 * {@code /siftec selftest quality}: the bugs the code quality pass found, each caught where a check can catch it. Every
 * check prints "SELFTEST quality"; failures say FAIL; the last line counts them.
 * <ol>
 * <li>a delayed check that throws is a FAIL and the suite still finishes ({@link DelayedChecks}, on a scratch queue
 * and report: its "(scratch)" FAIL line and logged error are on purpose);</li>
 * <li>an item with no lock is refused cleanly or not at all, never by a missing lock ({@link Locks});</li>
 * <li>a depot window's check never loads the depot's chunk ({@link DepotMenu#valid});</li>
 * <li>a vehicle's count of items moved is saved with it;</li>
 * <li>an unreadable Wormhole Gateway record is cleared, not read again at every check ({@link Prefabs#gatewayOf}).</li>
 * </ol>
 */
public final class QualityFixTests {
    private static final String COMPANY = "selftest_quality";
    private static final UUID MEMBER = UUID.fromString("5e1f7e57-0a11-4000-8000-000000000001");
    /** A spot far from every other test and from spawn, so its chunk is not loaded. */
    private static final BlockPos FAR = new BlockPos(-21_000, 80, 21_000);

    private static final TestReport REPORT = new TestReport("SELFTEST quality ");

    private QualityFixTests() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("quality").executes(QualityFixTests::run)))));
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        REPORT.expect(source, what, ok, detail);
    }

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        REPORT.reset();
        REPORT.attempt(source, "1 delayed checks", () -> delayed(source));
        REPORT.attempt(source, "2 locks", () -> locks(source, level));
        REPORT.attempt(source, "3 depot window", () -> depot(source, level));
        REPORT.attempt(source, "4 vehicle save", () -> vehicle(source, level));
        REPORT.attempt(source, "5 gateway record", () -> gateway(source, level));
        REPORT.report(source, "done: " + REPORT.totals());
        return 1;
    }

    private static Company company(ServerLevel level) {
        CompanyData data = CompanyData.get(level.getServer());
        Company c = data.companies().get(COMPANY);
        if (c == null) {
            c = new Company();
            c.id = COMPANY;
            c.name = "Selftest Quality";
            data.companies().put(COMPANY, c);
        }
        if (!c.members.contains(MEMBER.toString())) c.members.add(MEMBER.toString());
        data.setDirty();
        return c;
    }

    private static FakePlayer member(ServerLevel level) {
        company(level);
        FakePlayer fake = FakePlayer.get(level, new GameProfile(MEMBER, "selftest_quality"));
        fake.getInventory().clearContent();
        return fake;
    }

    // ------------------------------------------------------------------------------------------------ 1

    /** Three delayed checks, the first of which throws: the throw is one FAIL, the others still run, and it finishes. */
    private static void delayed(CommandSourceStack source) {
        TestQueue queue = new TestQueue();
        TestReport scratch = new TestReport("(scratch) ");
        DelayedChecks checks = new DelayedChecks(queue, scratch);
        checks.start(source);
        boolean[] ran = {false, false}, finished = {false};
        checks.at(1, () -> {
            throw new IllegalStateException("thrown on purpose by /siftec selftest quality");
        });
        checks.at(2, () -> ran[0] = true);
        checks.at(3, () -> ran[1] = true);
        checks.finish(0, () -> finished[0] = true);
        for (long t = 0; t <= 100 && !finished[0]; t++) queue.runDue(t);
        expect(source, "1: a delayed check that throws is one FAIL, the checks after it still run, and the suite finishes",
            scratch.failed == 1 && ran[0] && ran[1] && finished[0] && checks.pending() == 0,
            scratch.totals() + ", later checks ran " + ran[0] + "/" + ran[1] + ", finished " + finished[0] + ", pending " + checks.pending());
    }

    // ------------------------------------------------------------------------------------------------ 2

    private static void locks(CommandSourceStack source, ServerLevel level) {
        FakePlayer player = member(level);
        Item free = null;
        for (Item item : BuiltInRegistries.ITEM) {
            if (item != Items.AIR && Locks.lockOf(item) == null) {
                free = item;
                break;
            }
        }
        Item locked = null;
        Company company = company(level);
        for (Milestone m : Milestones.all()) {
            if (m.items().isEmpty()) continue;
            Item item = BuiltInRegistries.ITEM.getValue(m.items().getFirst());
            if (item != Items.AIR && Locks.lockOf(item) != null && !company.has(Locks.lockOf(item).id())) {
                locked = item;
                break;
            }
        }
        expect(source, "2: an item nothing locks is not refused", free != null && !Locks.refused(player, new ItemStack(free)),
            free == null ? "no such item" : BuiltInRegistries.ITEM.getKey(free).toString());
        expect(source, "2: an item its company has not unlocked is refused", locked != null && Locks.refused(player, new ItemStack(locked)),
            locked == null ? "no such item" : BuiltInRegistries.ITEM.getKey(locked).toString());
        String threw = "";
        try {
            Locks.tellLocked(player, (Milestone) null);
            if (free != null) Locks.tellLocked(player, new ItemStack(free));
        } catch (RuntimeException e) {
            threw = e.toString();
        }
        expect(source, "2: telling a player about a missing lock says it is locked and never fails", threw.isEmpty(), threw);
    }

    // ------------------------------------------------------------------------------------------------ 3

    private static void depot(CommandSourceStack source, ServerLevel level) {
        Company company = company(level);
        FakePlayer player = member(level);
        int cx = FAR.getX() >> 4, cz = FAR.getZ() >> 4;
        if (level.hasChunk(cx, cz)) {
            expect(source, "3: (the far test spot's chunk was already loaded; nothing to check)", true, "");
            return;
        }
        // in reach of a depot spot whose chunk is not loaded: the window closes, and the chunk stays unloaded
        player.setPos(FAR.getX() + 0.5, FAR.getY(), FAR.getZ() + 2.5);
        boolean valid = DepotMenu.valid(player, company, FAR);
        expect(source, "3: a depot window at an unloaded spot closes without loading its chunk", !valid && !level.hasChunk(cx, cz),
            "valid " + valid + ", chunk loaded " + level.hasChunk(cx, cz));
    }

    // ------------------------------------------------------------------------------------------------ 4

    private static void vehicle(CommandSourceStack source, ServerLevel level) {
        VehicleEntity truck = new VehicleEntity(Vehicles.type(VehicleKind.TRUCK), level);
        truck.itemsMoved = 4321;
        truck.stopsMade = 7;
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        truck.saveWithoutId(out);
        CompoundTag saved = out.buildResult();
        VehicleEntity back = new VehicleEntity(Vehicles.type(VehicleKind.TRUCK), level);
        back.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), saved));
        expect(source, "4: a vehicle's items moved and stops made come back from its save", back.itemsMoved == 4321 && back.stopsMade == 7,
            back.itemsMoved + " items, " + back.stopsMade + " stops");
    }

    // ------------------------------------------------------------------------------------------------ 5

    private static void gateway(CommandSourceStack source, ServerLevel level) {
        Company company = company(level);
        String kept = company.gateway;
        for (String record : new String[]{"not a record", "minecraft:overworld|not a number"}) {
            company.gateway = record;
            BlockPos found = Prefabs.gatewayOf(level.getServer(), company);
            expect(source, "5: the unreadable gateway record \"" + record + "\" is cleared", found == null && company.gateway.isEmpty(),
                "found " + found + ", record now \"" + company.gateway + "\"");
        }
        company.gateway = kept;
    }
}
