package com.meakaandre.siftec.prefab;

import com.meakaandre.siftec.TestReport;
import com.meakaandre.siftec.block.Facing;
import com.meakaandre.siftec.building.BuildGunItem;
import com.meakaandre.siftec.building.BuildingPower;
import com.meakaandre.siftec.building.PowerRole;
import com.meakaandre.siftec.claim.Claims;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.HubBlockEntity;
import com.meakaandre.siftec.hub.HubMenu;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.power.PowerTests;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.registry.ModItems;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.content.kinetics.mixer.MechanicalMixerBlock;
import com.zurrtum.create.content.kinetics.press.MechanicalPressBlock;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.antarcticgardens.cna.content.electricity.connector.AbstractElectricalConnector;

import java.util.List;
import java.util.Set;

/**
 * Tests for the HUB and Wormhole Gateway structures, run by the automated smoke test:
 * {@code /siftec selftest prefab} (placing, refusals, parts per upgrade, the terminal, the freighter, storage,
 * picking up and putting down again, claims, the Gateway and its phases), {@code /siftec selftest prefab check}
 * (the burners turn), {@code /siftec selftest prefab restart} (all of it is still there after a restart), and
 * {@code /siftec worldtest sathub <stage>} which sets up the client screenshots.
 */
public final class PrefabTests {
    /** Test sites, far from spawn and each other, on platforms high in the air. */
    private static final int Y = 230, AX = 2000, AZ = 2000, BX = 2400, BZ = 2000, GZ = 2100, VX = -2000, VZ = 2000;
    private static final String A = "selftest_hub_a", B = "selftest_hub_b";

    private static final TestReport REPORT = new TestReport("SELFTEST prefab ");

    private PrefabTests() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (pending != null && server.overworld().getGameTime() >= pendingAt) {
                Runnable run = pending;
                pending = null;
                run.run();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> pending = null);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("prefab").executes(PrefabTests::run)
                    .then(Commands.literal("check").executes(PrefabTests::check))
                    .then(Commands.literal("restart").executes(PrefabTests::restart))
                    .then(Commands.literal("bed").executes(PrefabTests::bed))
                    .then(Commands.literal("placed").executes(PrefabTests::placed))
                    .then(Commands.literal("gun").executes(PrefabTests::gun))))
                .then(Commands.literal("worldtest").then(Commands.literal("sathub")
                    .then(Commands.argument("stage", StringArgumentType.word())
                        .executes(c -> stage(c, StringArgumentType.getString(c, "stage"), 0))
                        .then(Commands.argument("n", IntegerArgumentType.integer(0, 7))
                            .executes(c -> stage(c, StringArgumentType.getString(c, "stage"), IntegerArgumentType.getInteger(c, "n")))))))));
    }

    private static void report(CommandSourceStack source, String text) {
        REPORT.report(source, text);
    }

    private static Company company(ServerLevel level, String id, String name) {
        CompanyData data = CompanyData.get(level.getServer());
        Company c = data.companies().get(id);
        if (c == null) {
            c = new Company();
            c.id = id;
            c.name = name;
            data.companies().put(id, c);
        }
        return c;
    }

    /** Loads (and keeps loaded) the chunks of an area and lays a stone floor under it at Y - 1, with air above. */
    private static void platform(ServerLevel level, int x0, int z0, int x1, int z1, int height) {
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++)
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                level.setBlock(pos.set(x, Y - 1, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = Y; y < Y + height; y++) {
                    if (!level.getBlockState(pos.set(x, y, z)).isAir()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
    }

    private static Prefabs.Spot hubSpot(int x, int z, Direction facing) {
        return new Prefabs.Spot(Prefabs.Kind.HUB, new BlockPos(x, Y, z), facing);
    }

    private static boolean is(ServerLevel level, Prefabs.Spot spot, int[] at, Block block) {
        return level.getBlockState(spot.at(at[0], at[1], at[2])).is(block);
    }

    private static HubBlockEntity terminal(ServerLevel level, Prefabs.Spot spot) {
        return level.getBlockEntity(spot.terminal()) instanceof HubBlockEntity hub ? hub : null;
    }

    /** Parts present: T terminal, C Craft Station (Press and Mixer), S storage, 1 and 2 burners, B bed, D dock tower, F freighter. */
    private static String parts(ServerLevel level, Prefabs.Spot spot) {
        StringBuilder b = new StringBuilder();
        b.append(is(level, spot, Templates.HUB_TERMINAL, ModBlocks.HUB.get()) ? "T" : ".");
        b.append(level.getBlockState(spot.at(Templates.PRESS[0], Templates.PRESS[1], Templates.PRESS[2])).getBlock() instanceof MechanicalPressBlock
            && level.getBlockState(spot.at(Templates.MIXER[0], Templates.MIXER[1], Templates.MIXER[2])).getBlock() instanceof MechanicalMixerBlock ? "C" : ".");
        b.append(is(level, spot, Templates.STORAGE, ModBlocks.PERSONAL_STORAGE.get()) ? "S" : ".");
        b.append(is(level, spot, Templates.BURNER_1, ModBlocks.HUB_BURNER.get()) ? "1" : ".");
        b.append(is(level, spot, Templates.BED, ModBlocks.HUB_BED.get()) ? "B" : ".");
        b.append(is(level, spot, Templates.BURNER_2, ModBlocks.HUB_BURNER.get()) ? "2" : ".");
        b.append(is(level, spot, Templates.TOWER, ModBlocks.PREFAB.get("metal").get()) ? "D" : ".");
        b.append(is(level, spot, Templates.FREIGHTER, ModBlocks.PREFAB.get("white").get()) ? "F" : ".");
        return b.toString();
    }

    /** Pays a milestone in full through the terminal's belt intake, as a belt or funnel would. */
    private static boolean payByBelt(HubBlockEntity hub, Company company, Milestone m) {
        company.active = m.id();
        for (Milestone.Cost cost : m.cost()) {
            if (!cost.present()) continue;
            ItemStack one = cost.icon();
            long want = company.needed(m, cost);
            try (Transaction tx = Transaction.openOuter()) {
                long took = hub.intake.insert(ItemVariant.of(one), want, tx);
                tx.commit();
                if (took < want) return false;
            }
        }
        return company.has(m.id());
    }

    private static Item biomass() {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse("siftec:biomass"));
    }

    private static int count(List<ItemStack> stacks, Item item) {
        int n = 0;
        for (ItemStack s : stacks) if (s.is(item)) n += s.getCount();
        return n;
    }

    /** Structure blocks (and terminals) still standing in a box. */
    private static int partsIn(ServerLevel level, AABB box) {
        int n = 0;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY - Prefabs.MAX_DROP, box.minZ), BlockPos.containing(box.maxX - 1, box.maxY - 1, box.maxZ - 1))) {
            if (Prefabs.isPart(level.getBlockState(pos))) n++;
        }
        return n;
    }

    /** The rest of the test, run once the freshly generated test chunks have settled (their block entities tick then). */
    private static Runnable pending;
    private static long pendingAt;

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        platform(level, AX - 40, AZ - 40, AX + 40, AZ + 40, 32);
        platform(level, AX - 30, GZ - 60, AX + 30, GZ + 4, 64);
        platform(level, BX - 20, BZ - 30, BX + 20, BZ + 10, 32);
        pending = () -> runSettled(source);
        pendingAt = level.getGameTime() + 40;
        report(source, "test sites ready; the test runs in two seconds");
        return 1;
    }

    private static void runSettled(CommandSourceStack source) {
        ServerLevel level = source.getServer().overworld();
        Company a = company(level, A, "Selftest HUB A"), b = company(level, B, "Selftest HUB B");
        for (Company c : new Company[]{a, b}) {
            c.done.clear();
            c.paid.clear();
            c.active = "";
            c.lockUntil = 0;
            c.gateway = "";
            Claims.releaseCompany(source.getServer(), c.id);
        }
        Companies.save(source.getServer());
        report(source, "sizes: HUB " + Templates.hub().width() + " x " + Templates.hub().length() + " x " + Templates.hub().height()
            + ", Gateway " + Templates.gateway().width() + " x " + Templates.gateway().length() + " x " + Templates.gateway().height()
            + " (blocks: HUB " + Templates.hub().keys().size() + ", Gateway " + Templates.gateway().keys().size() + ")");

        // ---- refusals: something in the way, no ground, the spawn area, another company's land
        Prefabs.Spot spotA = hubSpot(AX, AZ, Direction.NORTH);
        BlockPos inside = spotA.at(2, 2, 3);
        level.setBlock(inside, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        Prefabs.Check blocked = Prefabs.check(level, spotA, a, false);
        level.setBlock(inside, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        Prefabs.Check floating = Prefabs.check(level, new Prefabs.Spot(Prefabs.Kind.HUB, new BlockPos(AX, Y + 12, AZ), Direction.NORTH), a, false);
        BlockPos spawn = level.getRespawnData().pos();
        for (int cx = (spawn.getX() >> 4) - 2; cx <= (spawn.getX() >> 4) + 2; cx++)
            for (int cz = (spawn.getZ() >> 4) - 3; cz <= (spawn.getZ() >> 4) + 2; cz++) level.getChunk(cx, cz);
        Prefabs.Check atSpawn = Prefabs.check(level, new Prefabs.Spot(Prefabs.Kind.HUB, spawn.above(40), Direction.NORTH), a, false);
        report(source, "refused: blocked " + !blocked.ok() + " (" + blocked.blocked.size() + " in the way), no ground " + (floating.noGround > 0)
            + ", spawn area " + "siftec.prefab.spawn".equals(atSpawn.reason));

        // ---- place the HUB: level 0 has the terminal and the craft bench only
        Prefabs.Check placed = Prefabs.place(level, spotA, a, null);
        HubBlockEntity hub = terminal(level, spotA);
        if (hub == null) {
            report(source, "placing failed: " + placed.message().getString() + " (" + placed.blocked.size() + " blocked, first " + (placed.blocked.isEmpty() ? "-" : placed.blocked.get(0).toShortString()) + ")");
            return;
        }
        boolean claimed = Claims.at(level, spotA.terminal()) != null && Claims.at(level, spotA.terminal()).company.equals(A);
        report(source, "placed " + placed.ok() + " (" + placed.message().getString() + "), controller " + (hub != null && hub.prefab) + ", claims land " + claimed
            + ", parts at upgrade 0 " + parts(level, spotA));
        Prefabs.Check foreign = Prefabs.check(level, hubSpot(AX, AZ + 30, Direction.NORTH), b, false);
        report(source, "refused on another company's land " + "siftec.prefab.claimed".equals(foreign.reason) + " (" + foreign.message().getString() + ")");

        // ---- every upgrade adds its part, as Satisfactory's HUB upgrades do
        StringBuilder levels = new StringBuilder(parts(level, spotA));
        for (int i = 1; i <= 6; i++) {
            a.done.add("hub_upgrade_" + i);
            Prefabs.refresh(hub);
            levels.append(" ").append(i).append(":").append(parts(level, spotA));
        }
        report(source, "parts per upgrade (T terminal, C craft station, S storage, 1 burner, B beds, 2 burner, D dock, F freighter): " + levels
            + "; built level " + hub.builtLevel);

        // ---- Personal Storage keeps what is put in
        StorageBoxBlockEntity box = level.getBlockEntity(spotA.at(Templates.STORAGE[0], Templates.STORAGE[1], Templates.STORAGE[2])) instanceof StorageBoxBlockEntity s ? s : null;
        if (box != null) {
            box.items.setItem(0, new ItemStack(ModItems.PARTS.get("iron_rod"), 10));
            box.items.setItem(5, new ItemStack(Items.DIAMOND, 3));
        }
        // ---- the terminal takes a milestone by belt; the freighter then leaves and comes back after the milestone's time
        Milestone base = Milestones.get("base_building");
        boolean paid = payByBelt(hub, a, base);
        long lock = a.lockUntil - level.getGameTime();
        Prefabs.refresh(hub);
        String away = parts(level, spotA);
        a.lockUntil = level.getGameTime();
        Prefabs.refresh(hub);
        String back = parts(level, spotA);
        report(source, "terminal: Base Building paid by belt " + paid + ", freighter away for " + lock + " ticks (milestone " + base.seconds() + " s): parts "
            + away + " while away, " + back + " when back");

        // ---- burners: fuel for both; whether they turn is checked a little later (check)
        for (int[] at : new int[][]{Templates.BURNER_1, Templates.BURNER_2}) {
            if (level.getBlockEntity(spotA.at(at[0], at[1], at[2])) instanceof HubBurnerBlockEntity engine) engine.addFuel(new ItemStack(biomass(), 8));
        }
        int coalIn = 0;
        for (int[] at : new int[][]{Templates.BURNER_1, Templates.BURNER_2}) {
            if (level.getBlockEntity(spotA.at(at[0], at[1], at[2])) instanceof HubBurnerBlockEntity engine) coalIn += engine.fuel.getItem(0).getCount();
        }

        // ---- pick it up: the item, the storage and the fuel come back; nothing is left standing; the land is free again
        AABB boundsA = Prefabs.bounds(spotA);
        List<ItemStack> back1 = Prefabs.dismantle(level, hub);
        boolean released = Claims.at(level, spotA.terminal()) == null;
        report(source, "picked up: HUB items (none any more) " + back1.stream().filter(st -> st.getItem().builtInRegistryHolder().key().identifier().getPath().equals("hub")).count() + ", iron rods " + count(back1, ModItems.PARTS.get("iron_rod"))
            + " of 10, diamonds " + count(back1, Items.DIAMOND) + " of 3, biomass " + count(back1, biomass()) + " of " + coalIn
            + "; parts left standing " + partsIn(level, boundsA) + "; land released " + released + "; progress kept " + a.has("hub_upgrade_6"));

        // ---- put it down again, turned east: it claims again and comes back at the company's level
        Prefabs.Spot spotA2 = hubSpot(AX, AZ, Direction.EAST);
        Prefabs.Check again = Prefabs.place(level, spotA2, a, null);
        HubBlockEntity hub2 = terminal(level, spotA2);
        boolean reclaimed = Claims.at(level, spotA2.terminal()) != null && Claims.at(level, spotA2.terminal()).company.equals(A);
        BlockState term = level.getBlockState(spotA2.terminal());
        report(source, "placed again facing east " + again.ok() + ": parts " + parts(level, spotA2) + ", claims again " + reclaimed
            + ", terminal faces " + term.getValue(Facing.FACING) + ", level " + (hub2 == null ? -1 : hub2.builtLevel));

        // ---- a second company's HUB, all upgrades, with fuel: its burners are checked later
        for (int i = 1; i <= 6; i++) b.done.add("hub_upgrade_" + i);
        Prefabs.Spot spotB = hubSpot(BX, BZ, Direction.NORTH);
        Prefabs.Check placedB = Prefabs.place(level, spotB, b, null);
        for (int[] at : new int[][]{Templates.BURNER_1, Templates.BURNER_2}) {
            if (level.getBlockEntity(spotB.at(at[0], at[1], at[2])) instanceof HubBurnerBlockEntity engine) engine.addFuel(new ItemStack(biomass(), 16));
        }
        // the two burners wired to each other, so they are a grid (they give power only to what the grid uses)
        PowerTests.wire(
            level.getBlockEntity(spotB.at(Templates.BURNER_1[0], Templates.BURNER_1[1] + 1, Templates.BURNER_1[2])) instanceof AbstractElectricalConnector ca ? ca : null,
            level.getBlockEntity(spotB.at(Templates.BURNER_2[0], Templates.BURNER_2[1] + 1, Templates.BURNER_2[2])) instanceof AbstractElectricalConnector cb ? cb : null);
        report(source, "second company's HUB " + placedB.ok() + " (" + placedB.message().getString() + "), parts " + parts(level, spotB));

        // ---- the Wormhole Gateway: placed whole, a phase paid lights its ring, one per company, picked up and put back with its progress
        Prefabs.Spot spotG = new Prefabs.Spot(Prefabs.Kind.GATEWAY, new BlockPos(AX, Y, GZ), Direction.NORTH);
        Prefabs.Check placedG = Prefabs.place(level, spotG, a, null);
        HubBlockEntity gate = terminal(level, spotG);
        String ring0 = ring(level, spotG);
        boolean phase1 = gate != null && payByBelt(gate, a, Milestones.get("phase_1"));
        if (gate != null) Prefabs.refresh(gate);
        String ring1 = ring(level, spotG);
        Prefabs.Check second = Prefabs.check(level, new Prefabs.Spot(Prefabs.Kind.GATEWAY, new BlockPos(AX, Y, GZ + 2), Direction.NORTH), a, false);
        List<ItemStack> back2 = gate == null ? List.of() : Prefabs.dismantle(level, gate);
        int leftG = partsIn(level, Prefabs.bounds(spotG));
        Prefabs.Check againG = Prefabs.place(level, spotG, a, null);
        report(source, "gateway placed " + placedG.ok() + " (" + placedG.message().getString() + "), ring gate " + ring0 + " -> phase 1 paid " + phase1 + " -> " + ring1
            + "; second gateway refused " + "siftec.prefab.one_gateway".equals(second.reason) + "; picked up: stacks back " + back2.size()
            + ", parts left " + leftG + "; placed again " + againG.ok() + " with ring gate " + ring(level, spotG));
        // the portal: inside the ring, in its middle layer, and nowhere else
        boolean portalIn = Templates.inPortal(0, Templates.RING_Y + 0.5, Templates.RING_Z + 0.5) && Templates.inPortal(-8, Templates.RING_Y + 6, Templates.RING_Z + 0.5);
        boolean portalOut = !Templates.inPortal(0, Templates.RING_Y + Templates.RING_OUTER, Templates.RING_Z + 0.5) && !Templates.inPortal(0, Templates.RING_Y + 0.5, Templates.RING_Z + 4)
            && !Templates.inPortal(Templates.RING_INNER + 1, Templates.RING_Y + 0.5, Templates.RING_Z + 0.5);
        report(source, "gateway portal: walking in at the middle of the ring counts " + portalIn + "; beside it, in front of it or in the ring does not " + portalOut);

        // ---- an old single-block HUB still loads and works as a terminal
        BlockPos legacy = new BlockPos(AX + 30, Y + 1, AZ - 30);
        level.setBlockAndUpdate(legacy, ModBlocks.HUB.get().defaultBlockState());
        HubBlockEntity old = level.getBlockEntity(legacy) instanceof HubBlockEntity h ? h : null;
        report(source, "old single-block HUB: block entity " + (old != null) + ", structure " + (old != null && old.prefab));
        if (old != null) Prefabs.dismantle(level, old);
        Companies.save(source.getServer());
    }

    /**
     * The ring gate's parts, one mark each: the cradle (Phase 1), the ring's bottom, side and top (Phases 2 to 4) as # when
     * built and . when not; then the lining, o dark or * lit; then + if the portal is open.
     */
    private static String ring(ServerLevel level, Prefabs.Spot spot) {
        StringBuilder b = new StringBuilder();
        int[][] marks = Templates.gatewayMarks();
        for (int i = 0; i < 4; i++) b.append(Prefabs.isPart(level.getBlockState(spot.at(marks[i][0], marks[i][1], marks[i][2]))) ? "#" : ".");
        BlockState lining = level.getBlockState(spot.at(marks[4][0], marks[4][1], marks[4][2]));
        b.append(lining.is(ModBlocks.PREFAB.get("coil_lit").get()) ? "*" : lining.is(ModBlocks.PREFAB.get("coil").get()) ? "o" : ".");
        if (level.getBlockState(spot.at(0, Templates.RING_Y, Templates.RING_Z)).is(ModBlocks.PREFAB.get("portal").get())) b.append("+");
        return b.toString();
    }

    private static int check(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        Prefabs.Spot spotB = hubSpot(BX, BZ, Direction.NORTH);
        StringBuilder b = new StringBuilder();
        for (int[] at : new int[][]{Templates.BURNER_1, Templates.BURNER_2}) {
            if (level.getBlockEntity(spotB.at(at[0], at[1], at[2])) instanceof HubBurnerBlockEntity engine) {
                b.append(" burner ").append(engine.status().getString()).append(" (fuel ").append(engine.fuel.getItem(0)).append(", banked ")
                    .append(Math.round(engine.energy)).append(" SU-ticks, biomass ").append(BuildingPower.itemMj(PowerRole.BIOMASS, new ItemStack(biomass())))
                    .append(" MJ, coal ").append(BuildingPower.itemMj(PowerRole.BIOMASS, new ItemStack(Items.COAL))).append(" MJ, grid ")
                    .append(engine.grid == null ? "none" : engine.grid.describe()).append(");");
            } else {
                b.append(" burner missing;");
            }
        }
        BlockPos term = spotB.terminal();
        HubBlockEntity hub = terminal(level, spotB);
        HubBlockEntity hubA = terminal(level, hubSpot(AX, AZ, Direction.EAST));
        b.append(" first company's terminal ticked ").append(hubA == null ? -1 : hubA.ticked).append(";");
        report(source, "check: the second company's HUB burners:" + b + " terminal ticked " + (hub == null ? -1 : hub.ticked) + ", blocks tick here "
            + level.shouldTickBlocksAt(term) + ", entity ticking " + level.isPositionEntityTicking(term) + ", chunk forced "
            + level.getForceLoadedChunks().contains(ChunkPos.pack(term.getX() >> 4, term.getZ() >> 4)));
        return 1;
    }

    private static int restart(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        Prefabs.Spot spotA2 = hubSpot(AX, AZ, Direction.EAST);
        level.getChunk(spotA2.terminal());
        HubBlockEntity hub = terminal(level, spotA2);
        Prefabs.Spot spotG = new Prefabs.Spot(Prefabs.Kind.GATEWAY, new BlockPos(AX, Y, GZ), Direction.NORTH);
        level.getChunk(spotG.terminal());
        HubBlockEntity gate = terminal(level, spotG);
        Company a = CompanyData.get(source.getServer()).byId(A);
        report(source, "after restart: HUB structure " + (hub != null && hub.prefab) + " facing " + (hub == null ? null : hub.facing) + " level " + (hub == null ? -1 : hub.builtLevel)
            + ", parts " + parts(level, spotA2) + ", claim " + (Claims.at(level, spotA2.terminal()) != null) + "; gateway structure " + (gate != null && gate.prefab)
            + " rings " + ring(level, spotG) + ", company knows it " + (a != null && Prefabs.gatewayOf(source.getServer(), a) != null));
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ client stages

    /** The showcase HUB the screenshots look at, for the players' own company. */
    private static Prefabs.Spot view() {
        return hubSpot(VX, VZ, Direction.NORTH);
    }

    private static int stage(CommandContext<CommandSourceStack> context, String stage, int n) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        List<ServerPlayer> players = source.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) return 0;
        ServerPlayer player = players.get(0);
        Company company = Companies.of(player);
        Prefabs.Spot spot = view();
        switch (stage) {
            case "ground" -> {
                // a clear site; the player stands south of it, looking at the ground where the HUB's front edge goes
                platform(level, VX - 40, VZ - 100, VX + 50, VZ + 20, 64);
                company.lockUntil = 0;
                // eye 1.62 above the floor, 10 blocks short of the spot: looking 9.2 degrees down meets the middle of the front edge's block
                player.teleportTo(level, VX + 0.5, Y, VZ + 10.5, Set.of(), 180f, 9.2f, true);
            }
            case "obstacle" -> {
                // a pillar where the hall goes turns the hologram red
                for (int y = 0; y < 4; y++) level.setBlockAndUpdate(spot.at(-3, y, 4), Blocks.COBBLESTONE.defaultBlockState());
            }
            case "clear" -> {
                for (int y = 0; y < 4; y++) level.setBlockAndUpdate(spot.at(-3, y, 4), Blocks.AIR.defaultBlockState());
            }
            case "level" -> {
                // the company at HUB upgrade n (7: upgrade 6 with the freighter docked); the showcase HUB is put down if it is not there
                if (!(level.getBlockEntity(spot.terminal()) instanceof HubBlockEntity)) {
                    Prefabs.Check c = Prefabs.place(level, spot, company, null);
                    report(source, "sathub showcase HUB placed " + c.ok() + " " + c.message().getString());
                }
                company.done.removeIf(id -> id.startsWith("hub_upgrade_") || id.startsWith("phase_"));
                for (int i = 1; i <= Math.min(6, n); i++) company.done.add("hub_upgrade_" + i);
                company.lockUntil = n == 6 ? level.getGameTime() + 20 * 600 : 0;
                Companies.save(source.getServer());
                if (level.getBlockEntity(spot.terminal()) instanceof HubBlockEntity hub) Prefabs.refresh(hub);
            }
            case "outside" -> {
                // spectator view from the front left, a mannequin at the entrance for scale
                mannequin(level, spot.at(2, 1, -1));
                player.teleportTo(level, VX - 24.5, Y + 16, VZ + 22.5, Set.of(), -145f, 16f, true);
            }
            case "side" -> player.teleportTo(level, VX + 22.5, Y + 12, VZ - 6.5, Set.of(), 105f, 22f, true);
            case "inside" -> player.teleportTo(level, VX + 2.5, Y + 1, VZ - 0.5, Set.of(), 155f, 8f, true);
            case "terminal" -> {
                player.teleportTo(level, VX + 0.5, Y + 1, VZ - 2.5, Set.of(), 180f, 20f, true);
                if (level.getBlockEntity(spot.terminal()) instanceof HubBlockEntity hub) HubMenu.open(player, company, spot.terminal(), false);
            }
            case "gateway" -> {
                // the company's Wormhole Gateway at n phases, north of the HUB, seen from far off with a mannequin at its terminal
                Prefabs.Spot g = new Prefabs.Spot(Prefabs.Kind.GATEWAY, new BlockPos(VX, Y, VZ - 40), Direction.NORTH);
                company.done.removeIf(id -> id.startsWith("phase_"));
                for (int i = 1; i <= 6; i++) company.done.add("hub_upgrade_" + i);
                for (int i = 1; i <= Math.min(5, n); i++) company.done.add("phase_" + i);
                Companies.save(source.getServer());
                if (!(level.getBlockEntity(g.terminal()) instanceof HubBlockEntity)) {
                    company.gateway = "";
                    Prefabs.Check c = Prefabs.place(level, g, company, null);
                    report(source, "sathub gateway placed " + c.ok() + " " + c.message().getString());
                }
                if (level.getBlockEntity(g.terminal()) instanceof HubBlockEntity gate) Prefabs.refresh(gate);
                mannequin(level, g.at(2, 0, -1));
                player.teleportTo(level, VX + 40.5, Y + 20, VZ - 10.5, Set.of(), 145f, -8f, true);
            }
            default -> {
                report(source, "unknown stage " + stage);
                return 0;
            }
        }
        report(source, "sathub " + stage + " " + n + ": parts " + parts(level, spot));
        return 1;
    }

    private static void mannequin(ServerLevel level, BlockPos at) {
        AABB near = new AABB(at).inflate(2);
        if (!level.getEntitiesOfClass(LivingEntity.class, near, e -> !(e instanceof ServerPlayer)).isEmpty()) return;
        var registry = BuiltInRegistries.ENTITY_TYPE;
        EntityType<?> type = registry.getOptional(Identifier.withDefaultNamespace("mannequin"))
            .or(() -> registry.getOptional(Identifier.withDefaultNamespace("armor_stand"))).orElse(null);
        if (type == null) return;
        var entity = type.create(level, EntitySpawnReason.COMMAND);
        if (entity == null) return;
        entity.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 180f, 0f);
        level.addFreshEntity(entity);
    }

    /** The real player uses a bed of the showcase HUB (upgrade 4 or later): the respawn point moves there. */
    private static int bed(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        List<ServerPlayer> players = source.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) return 0;
        ServerPlayer player = players.get(0);
        Prefabs.Spot spot = view();
        BlockPos bed = spot.at(Templates.BED[0], Templates.BED[1], Templates.BED[2]);
        BlockPos stand = spot.at(Templates.BED[0] + 1, 1, Templates.BED[2]);
        player.teleportTo(level, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, Set.of(), 0f, 30f, true);
        BlockState state = level.getBlockState(bed);
        var result = state.useWithoutItem(level, player, new BlockHitResult(Vec3.atCenterOf(bed), Direction.UP, bed, false));
        var config = player.getRespawnConfig();
        BlockPos at = config == null ? null : config.respawnData().pos();
        report(source, "bed: block " + state.getBlock().getClass().getSimpleName() + ", use " + result + ", respawn point " + (at == null ? "none" : at.toShortString())
            + " (bed at " + bed.toShortString() + "), set to the bed " + (at != null && at.distManhattan(bed) <= 1));
        player.stopSleeping();
        return 1;
    }

    /** Right after the client player joined: they have the Build Gun (every player spawns with it) and no HUB item. */
    private static int gun(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<ServerPlayer> players = source.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) return 0;
        ServerPlayer player = players.get(0);
        int guns = 0;
        boolean hubItem = false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (st.getItem() instanceof BuildGunItem) guns += st.getCount();
            if (!st.isEmpty() && st.getItem().builtInRegistryHolder().key().identifier().toString().equals("siftec:hub")) hubItem = true;
        }
        report(source, "joined: the player has " + guns + " Build Gun, a HUB item " + hubItem + "; HUB block has an item " + (ModBlocks.HUB.get().asItem() != Items.AIR)
            + "; spawns with the gun " + (guns == 1 && !hubItem));
        return 1;
    }

    /** After the client player built the HUB with the Build Gun: is it there, and were its 20 Raw Iron taken (survival)? */
    private static int placed(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        List<ServerPlayer> players = source.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) return 0;
        ServerPlayer player = players.get(0);
        HubBlockEntity hub = terminal(level, view());
        int items = player.getInventory().countItem(Items.RAW_IRON);
        String found = hub != null ? "on the spot" : "not found";
        if (hub == null) {
            BlockPos at = view().terminal();
            for (BlockPos pos : BlockPos.betweenClosed(at.offset(-8, -2, -8), at.offset(8, 2, 20))) {
                if (level.getBlockEntity(pos) instanceof HubBlockEntity h && h.prefab) {
                    found = "off by " + (pos.getX() - at.getX()) + "," + (pos.getY() - at.getY()) + "," + (pos.getZ() - at.getZ()) + ", moved onto the spot";
                    Prefabs.dismantle(level, h);
                    Prefabs.place(level, view(), Companies.of(player), null);
                    hub = terminal(level, view());
                    break;
                }
            }
        }
        report(source, "the player's HUB was " + found);
        report(source, "placed by the player: structure " + (hub != null && hub.prefab) + ", parts " + parts(level, view()) + ", Raw Iron left " + items + " (of 20)"
            + ", gamemode " + player.gameMode() + ", claims " + (Claims.at(level, view().terminal()) != null));
        return 1;
    }
}
