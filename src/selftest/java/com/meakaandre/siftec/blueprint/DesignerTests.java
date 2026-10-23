package com.meakaandre.siftec.blueprint;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.backpack.Carried;
import com.meakaandre.siftec.building.BuildGun;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.BuildingTemplate;
import com.meakaandre.siftec.building.BuildingTests;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.Category;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.Logic;
import com.meakaandre.siftec.building.Placement;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.registry.ModItems;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /siftec selftest designer}: the Blueprint Designers as Build Gun buildings. For each of Mk.1, Mk.2 and Mk.3: it
 * is on the Special tab, locked until its FICSIT Blueprints milestone, and the Build Gun puts it down for its 1.0 cost;
 * the build volume is the open cube over its floor (9, 13 or 17 blocks each way), no block of the building and free for
 * blueprints and flight; its floor and terminal cannot be mined; paper at the terminal saves a small structure built
 * inside, and that blueprint builds it again; a bigger Mk does not go over a smaller one; the Build Gun's dismantle gives
 * the cost back and leaves the structure inside (and the torch on the floor) where it was. Every check prints
 * "SELFTEST designer"; failures say FAIL; the last line counts them.
 */
public final class DesignerTests {
    private static final int X = -11200, Y = 200, Z = 11200;
    private static final String COMPANY = "selftest_designer";
    private static final UUID MEMBER = UUID.fromString("5e1f7e57-de51-4000-8000-000000000001");
    private static final String[] TIERS = {"blueprint_designer", "blueprint_designer_mk2", "blueprint_designer_mk3"};
    private static final String[] UNLOCKS = {"ficsit_blueprints", "ficsit_blueprints_mk2", "ficsit_blueprints_mk3"};
    private static final int[] SIDES = {9, 13, 17};

    private record Later(long at, Runnable run) {
    }

    private static final List<Later> LATER = new ArrayList<>();
    private static int passed, failed;

    private DesignerTests() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = server.overworld().getGameTime();
            for (Later l : new ArrayList<>(LATER)) {
                if (l.at > now) continue;
                LATER.remove(l);
                l.run.run();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LATER.clear());
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("designer").executes(DesignerTests::run)))
                .then(Commands.literal("worldtest").then(Commands.literal("designer").then(Commands.literal("view")
                    .then(Commands.argument("n", IntegerArgumentType.integer(0, 5))
                        .executes(c -> view(c, IntegerArgumentType.getInteger(c, "n")))))))));
    }

    private static void report(CommandSourceStack source, String text) {
        String line = "SELFTEST designer " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        report(source, (ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        passed = failed = 0;
        BuildingTemplate.forget();
        BuildingTests.platform(level, X - 16, Z - 40, X + 150, Z + 40, Y, 26);
        LATER.add(new Later(level.getServer().overworld().getGameTime() + 10, () -> {
            for (int k = 0; k < TIERS.length; k++) {
                int tier = k;
                try {
                    tier(source, level, tier);
                } catch (RuntimeException e) {
                    Siftec.LOGGER.error("SIFTEC: selftest designer {} threw", TIERS[tier], e);
                    expect(source, TIERS[tier] + " runs to the end", false, e.toString());
                }
            }
            report(source, "done: " + passed + " passed, " + failed + " failed");
        }));
        report(source, "test site ready; the checks report in a moment");
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ camera spots

    /** Where the camera views stand: a row of their own, clear of the selftest's site. */
    private static final int VZ = Z + 140;

    /**
     * {@code /siftec worldtest designer view <n>}, for screenshots: n 0 builds Mk.1, Mk.2 and Mk.3 in a row (creative,
     * no company); even n puts every player outside tier n/2, odd n on its floor at the back, facing its terminal.
     */
    private static int view(CommandContext<CommandSourceStack> context, int n) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        var players = source.getServer().getPlayerList().getPlayers();
        if (n == 0) {
            BuildingTemplate.forget();
            BuildingTests.platform(level, X - 16, VZ - 40, X + 150, VZ + 30, Y, 26);
            for (int k = 0; k < TIERS.length; k++) {
                BuildingDef def = Buildings.def(TIERS[k]);
                if (def == null) continue;
                Placement p = new Placement(def, new BlockPos(X + 50 * k, Y, VZ), Direction.NORTH, false);
                Buildings.Check check = Buildings.place(level, p, null, null, true);
                report(source, "view: " + TIERS[k] + " " + (check.ok() ? "built" : check.message().getString()) + " at " + p.anchor().toShortString());
            }
        }
        int k = n / 2;
        BuildingDef def = Buildings.def(TIERS[k]);
        BuildingTemplate t = def == null ? null : BuildingTemplate.of(def);
        if (t == null) return 0;
        Placement p = new Placement(def, new BlockPos(X + 50 * k, Y, VZ), Direction.NORTH, false);
        if (!(level.getBlockEntity(p.at(t.controller)) instanceof DesignerBlockEntity designer)) {
            report(source, "view " + n + ": no " + TIERS[k] + " at " + p.at(t.controller).toShortString());
            return 0;
        }
        BlockPos terminal = designer.getBlockPos();
        AABB volume = designer.volume();
        Vec3 centre = volume.getCenter(), at, look;
        int side = SIDES[k];
        if (n % 2 == 0) {
            // outside: in front of the terminal (south) and off to one side, above the frame's height
            at = new Vec3(centre.x + side * 0.6, Y + side * 0.8 + 3, terminal.getZ() + side * 1.1 + 4);
            look = centre;
        } else {
            // on the floor at the back of the volume, looking across it at the terminal
            at = new Vec3(centre.x + 0.5, volume.minY, volume.minZ + 1.5);
            look = Vec3.atCenterOf(terminal);
        }
        for (var player : players) {
            player.teleportTo(level, at.x, at.y, at.z, Set.of(), player.getYRot(), player.getXRot(), true);
            player.lookAt(EntityAnchorArgument.Anchor.EYES, look);
        }
        report(source, "view " + n + " ready: " + TIERS[k] + " from " + String.format(Locale.ROOT, "%.1f %.1f %.1f", at.x, at.y, at.z));
        return 1;
    }

    private static Company company(ServerLevel level) {
        CompanyData data = CompanyData.get(level.getServer());
        Company c = new Company();
        c.id = COMPANY;
        c.name = COMPANY;
        c.members.add(MEMBER.toString());
        c.owner = MEMBER.toString();
        data.companies().put(COMPANY, c);
        data.setDirty();
        return c;
    }

    /** One of each cost into the player's inventory, as many as it costs (a tag's cost as one item of the tag). */
    private static void pay(FakePlayer player, List<Milestone.Cost> costs) {
        for (Milestone.Cost cost : costs) {
            if (!cost.present()) continue;
            ItemStack kind = cost.icon();
            int left = cost.count();
            while (left > 0) {
                int n = Math.min(left, kind.getMaxStackSize());
                player.getInventory().placeItemBackInInventory(kind.copyWithCount(n), Prediction.SERVER_ONLY);
                left -= n;
            }
        }
    }

    /** How many of each cost the player carries. */
    private static Map<String, Integer> carried(FakePlayer player, List<Milestone.Cost> costs) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Milestone.Cost cost : costs) {
            if (!cost.present()) continue;
            int n = 0;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (cost.matches(stack)) n += stack.getCount();
            }
            out.put(cost.key(), n);
        }
        return out;
    }

    private static boolean all(Map<String, Integer> have, List<Milestone.Cost> costs, boolean full) {
        for (Milestone.Cost cost : costs) {
            if (!cost.present()) continue;
            int n = have.getOrDefault(cost.key(), 0);
            if (full ? n != cost.count() : n != 0) return false;
        }
        return true;
    }

    private static void tier(CommandSourceStack source, ServerLevel level, int k) {
        String id = TIERS[k];
        int side = SIDES[k], reach = side / 2;
        BuildingDef def = Buildings.def(id);
        BuildingTemplate t = def == null ? null : BuildingTemplate.of(def);
        if (def == null || t == null) {
            expect(source, id + ": the building and its template exist", false, def == null ? "no building" : BuildingTemplate.problem(id));
            return;
        }
        expect(source, id + ": on the Special tab, unlocked by " + UNLOCKS[k] + ", at Satisfactory 1.0's cost",
            def.category() == Category.SPECIAL && def.unlock().equals(UNLOCKS[k]) && def.cost().size() == 4 && def.logic() == Logic.DESIGNER,
            def.category() + " " + def.unlock() + " " + def.cost().size() + " costs");
        expect(source, id + ": its terminal is the building's controller", t.controller.state().getBlock() instanceof DesignerBlock d && d.reach == reach,
            t.controller.state().toString());

        Company company = company(level);
        BlockPos anchor = new BlockPos(X + 50 * k, Y, Z + 20);
        // facing north: its front (the terminal) to the south, the volume stretching north from it
        Placement p = new Placement(def, anchor, Direction.NORTH, false);
        FakePlayer gunner = FakePlayer.get(level, new GameProfile(MEMBER, "selftest_designer"));
        gunner.setGameMode(GameType.SURVIVAL);
        gunner.getInventory().clearContent();
        gunner.setPos(anchor.getX() + 0.5, Y, anchor.getZ() + 4.5);
        pay(gunner, def.cost());

        // locked until the milestone, then the Build Gun puts it down and takes the cost
        Buildings.Check locked = Buildings.check(level, p, company, Carried.of(gunner), false);
        expect(source, id + ": locked for a company without " + UNLOCKS[k], "siftec.build_gun.locked".equals(locked.reason), String.valueOf(locked.reason));
        company.done.add(UNLOCKS[k]);
        BuildGun.select(gunner, id, false, p);
        BuildGun.act(gunner, company);
        ControllerBlockEntity c = BuildGun.controllerAt(level, p.at(t.controller));
        if (!(c instanceof DesignerBlockEntity designer)) {
            Buildings.Check why = Buildings.check(level, p, company, Carried.of(gunner), false);
            expect(source, id + ": the Build Gun puts it down", false, why.message().getString()
                + (why.blocked.isEmpty() ? "" : " first blocked " + why.blocked.getFirst().toShortString()));
            return;
        }
        expect(source, id + ": the Build Gun puts it down and takes its cost", all(carried(gunner, def.cost()), def.cost(), false),
            carried(gunner, def.cost()).toString());

        // the volume: the open cube over the floor, behind the terminal
        AABB volume = designer.volume();
        boolean sized = volume.getXsize() == side && volume.getYsize() == side && volume.getZsize() == side;
        BlockPos terminal = designer.getBlockPos();
        boolean onFloor = (int) volume.minY == Y + 1 && (int) volume.minY == terminal.getY() && !volume.contains(Vec3.atCenterOf(terminal));
        int members = 0, floor = 0;
        for (BlockPos pos : BlockPos.betweenClosed((int) volume.minX, (int) volume.minY, (int) volume.minZ, (int) volume.maxX - 1, (int) volume.maxY - 1, (int) volume.maxZ - 1)) {
            if (Buildings.protectedAt(level, pos) || Buildings.within(level, pos) || Blueprints.reserved(level, pos)) members++;
        }
        for (BlockPos pos : BlockPos.betweenClosed((int) volume.minX, Y, (int) volume.minZ, (int) volume.maxX - 1, Y, (int) volume.maxZ - 1)) {
            if (Buildings.protectedAt(level, pos)) floor++;
        }
        expect(source, id + ": the build volume is " + side + " blocks each way, over the floor and level with the terminal",
            sized && onFloor, volume + ", terminal " + terminal.toShortString());
        expect(source, id + ": the volume is open (no block of the building, free for blueprints), the floor under it all the building's",
            members == 0 && floor == side * side, members + " cells taken in the volume, " + floor + " of " + side * side + " floor blocks");
        BlockPos middle = BlockPos.containing(volume.getCenter());
        gunner.setPos(middle.getX() + 0.5, middle.getY(), middle.getZ() + 0.5);
        boolean flies = DesignerBlockEntity.inside(gunner);
        gunner.setPos(anchor.getX() + 0.5, Y, anchor.getZ() + 4.5);
        expect(source, id + ": anyone in the volume can fly, nobody outside it", flies && !DesignerBlockEntity.inside(gunner), "inside " + flies);

        // only the Build Gun takes it down: a hand cannot mine its floor or its terminal
        BlockPos floorBlock = BlockPos.containing(volume.minX, Y, volume.minZ);
        BlockState floorWas = level.getBlockState(floorBlock), terminalWas = level.getBlockState(terminal);
        gunner.gameMode.destroyBlock(floorBlock);
        gunner.gameMode.destroyBlock(terminal);
        expect(source, id + ": its floor and terminal cannot be mined by hand",
            level.getBlockState(floorBlock) == floorWas && level.getBlockState(terminal) == terminalWas, "");

        // a small structure inside: three cobblestone and a stair standing on the floor, a torch on the floor
        BlockPos base = middle.atY(Y + 1);
        List<BlockPos> built = List.of(base, base.east(), base.east().above(), base.west());
        for (int i = 0; i < 3; i++) level.setBlock(built.get(i), Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(built.get(3), Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST), Block.UPDATE_ALL);
        BlockPos torch = base.north(2);
        level.setBlock(torch, Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
        List<BlockState> states = new ArrayList<>();
        for (BlockPos pos : built) states.add(level.getBlockState(pos));

        // paper at the terminal saves it
        gunner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PAPER));
        level.getBlockState(terminal).useItemOn(gunner.getMainHandItem(), level, gunner, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(terminal), Direction.SOUTH, terminal, false));
        ItemStack print = ItemStack.EMPTY;
        for (int i = 0; i < gunner.getInventory().getContainerSize(); i++) {
            if (gunner.getInventory().getItem(i).is(ModItems.BLUEPRINT.get())) print = gunner.getInventory().getItem(i).copy();
        }
        expect(source, id + ": paper at the terminal saves what is built inside (5 blocks)", !print.isEmpty() && Blueprints.count(print) == 5,
            print.isEmpty() ? "no blueprint" : Blueprints.count(print) + " blocks");
        for (int i = 0; i < gunner.getInventory().getContainerSize(); i++) {
            if (gunner.getInventory().getItem(i).is(ModItems.BLUEPRINT.get())) gunner.getInventory().setItem(i, ItemStack.EMPTY);
        }

        // the blueprint builds it again, out on the platform (a creative player carries every block)
        if (!print.isEmpty()) {
            BlockPos copy = new BlockPos(X + 50 * k, Y, Z - 20);
            gunner.setGameMode(GameType.CREATIVE);
            gunner.setPos(copy.getX() + 0.5, Y, copy.getZ() + 12.5);
            Blueprints.use(gunner, print, copy);
            Blueprints.use(gunner, print, copy);
            gunner.setGameMode(GameType.SURVIVAL);
            int found = 0;
            for (BlockPos pos : BlockPos.betweenClosed(copy.offset(-reach, 0, -reach), copy.offset(reach, side - 1, reach))) {
                BlockState s = level.getBlockState(pos);
                if (s.is(Blocks.COBBLESTONE) || s.is(Blocks.STONE_BRICK_STAIRS) || s.is(Blocks.TORCH)) {
                    found++;
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            expect(source, id + ": its blueprint builds the structure again", found == 5, found + " of 5 blocks");
        }

        // a bigger Mk does not go over it: it is dismantled and the new one built
        if (k + 1 < TIERS.length) {
            BuildingDef next = Buildings.def(TIERS[k + 1]);
            if (next != null) {
                company.done.add(UNLOCKS[k + 1]);
                Buildings.Check over = Buildings.check(level, new Placement(next, anchor, Direction.NORTH, false), company, null, true);
                expect(source, id + ": the next Mk cannot be put over it (no upgrade in place)", !over.ok(), over.message().getString());
            }
        }

        // the Build Gun's dismantle, aimed at the terminal: the cost comes back, the structure inside stays
        gunner.setPos(terminal.getX() + 0.5, Y, terminal.getZ() + 3.5);
        gunner.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(terminal));
        BuildGun.select(gunner, "", true, null);
        BuildGun.act(gunner, company);
        boolean gone = !(level.getBlockState(terminal).getBlock() instanceof DesignerBlock) && level.getBlockState(floorBlock).isAir()
            && !Buildings.protectedAt(level, floorBlock);
        expect(source, id + ": the Build Gun dismantles it (floor, frame and terminal gone)", gone,
            "terminal now " + level.getBlockState(terminal).getBlock() + ", floor now " + level.getBlockState(floorBlock).getBlock());
        Map<String, Integer> back = carried(gunner, def.cost());
        expect(source, id + ": dismantling gives its whole cost back", all(back, def.cost(), true), back.toString());
        boolean kept = true;
        for (int i = 0; i < built.size(); i++) kept &= level.getBlockState(built.get(i)) == states.get(i);
        boolean torchKept = level.getBlockState(torch).is(Blocks.TORCH);
        expect(source, id + ": what was built inside is untouched (the torch on the floor too)", kept && torchKept, "structure " + kept + ", torch " + torchKept);

        for (BlockPos pos : built) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(torch, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        gunner.getInventory().clearContent();
    }
}
