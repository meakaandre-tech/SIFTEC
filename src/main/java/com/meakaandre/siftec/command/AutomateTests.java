package com.meakaandre.siftec.command;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.power.PoleBlockEntity;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.workshop.WorkshopBlockEntity;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.AllRecipeTypes;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Selftests for automation: the Power Line pressed from Cable and a Workshop item made in a Mechanical Crafter only
 * for a company that has them unlocked; the Workshop fed and emptied through the item transfer API that belts,
 * funnels and chutes use; and a machine's fake player stringing a Power Line between two poles. Every line of
 * output starts with "SELFTEST automate".
 */
public final class AutomateTests {
    private static final String YES = "selftest_auto_yes", NO = "selftest_auto_no", MAM = "selftest_auto_mam";

    private AutomateTests() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("worldtest").then(Commands.literal("automate").executes(AutomateTests::run)))));
    }

    private static void report(CommandSourceStack source, String text) {
        String line = "SELFTEST automate " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        CompanyData data = CompanyData.get(source.getServer());
        Company yes = new Company(), no = new Company();
        yes.id = YES;
        no.id = NO;
        for (Milestone m : Milestones.all()) yes.done.add(m.id());
        data.companies().put(yes.id, yes);
        data.companies().put(no.id, no);
        int x = 40, z = -40;
        level.getChunkAt(new BlockPos(x, 0, z));
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 12;
        BlockPos at = new BlockPos(x, y, z);
        level.setChunkForced(x >> 4, z >> 4, true);
        try {
            machines(source, level, at);
            workshop(source, level, at.east(4));
            poles(source, level, at.south(4));
            mam(source, level, at.west(4), data);
            deployer(source, level, at.north(6));
        } catch (RuntimeException e) {
            report(source, "FAILED with " + e);
            Siftec.LOGGER.error("automate selftest", e);
        } finally {
            level.setChunkForced(x >> 4, z >> 4, false);
            data.companies().remove(YES);
            data.companies().remove(NO);
            data.companies().remove(MAM);
        }
        return 1;
    }

    /** A Mechanical Press and a Mechanical Crafter, each asking for its recipe as it does while it ticks. */
    private static void machines(CommandSourceStack source, ServerLevel level, BlockPos at) {
        level.setBlockAndUpdate(at, BuiltInRegistries.BLOCK.getValue(Identifier.parse("create:mechanical_press")).defaultBlockState());
        level.setBlockAndUpdate(at.east(), BuiltInRegistries.BLOCK.getValue(Identifier.parse("create:mechanical_crafter")).defaultBlockState());
        BlockEntity press = level.getBlockEntity(at), crafter = level.getBlockEntity(at.east());
        if (press == null || crafter == null) {
            report(source, "machines: press or crafter has no block entity");
            return;
        }
        SingleRecipeInput cable = new SingleRecipeInput(new ItemStack(item("siftec:cable")));
        // the Portable Miner's crafter recipe: 1 Mechanical Drill, 2 Iron Sheet, 4 Iron Rod, three to a row
        ItemStack drill = new ItemStack(item("create:mechanical_drill")), sheet = new ItemStack(item("create:iron_sheet")), rod = new ItemStack(item("siftec:iron_rod"));
        CraftingInput grid = CraftingInput.of(3, 3, List.of(drill, sheet, sheet, rod, rod, rod, rod, ItemStack.EMPTY, ItemStack.EMPTY));
        StringBuilder said = new StringBuilder();
        for (String company : List.of(YES, NO)) {
            press.setAttached(Ownership.OWNER, company);
            crafter.setAttached(Ownership.OWNER, company);
            String line, miner;
            Ownership.ticking(press);
            try {
                line = level.recipeAccess().getRecipeFor(AllRecipeTypes.PRESSING, cable, level)
                    .map(h -> h.value().assemble(cable, level.getRandom()).toString()).orElse("refused");
            } finally {
                Ownership.ticking(null);
            }
            Ownership.ticking(crafter);
            try {
                miner = level.recipeAccess().getRecipeFor(AllRecipeTypes.MECHANICAL_CRAFTING, grid, level)
                    .map(h -> h.value().assemble(grid).toString()).orElse("refused");
            } finally {
                Ownership.ticking(null);
            }
            said.append(company.equals(YES) ? "unlocked company: " : "; locked company: ").append("press on Cable -> ").append(line)
                .append(", crafter on the Portable Miner grid -> ").append(miner);
        }
        report(source, "machines " + said);
        level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(at.east(), Blocks.AIR.defaultBlockState());
    }

    /** A Workshop picks the Node Scanner (2 Iron Sheet, 4 Wire), is fed through the transfer API and emptied the same way. */
    private static void workshop(CommandSourceStack source, ServerLevel level, BlockPos at) {
        for (String company : List.of(YES, NO)) {
            level.setBlockAndUpdate(at, ModBlocks.EQUIPMENT_WORKSHOP.get().defaultBlockState());
            if (!(level.getBlockEntity(at) instanceof WorkshopBlockEntity workshop)) {
                report(source, "workshop: no block entity");
                return;
            }
            workshop.setAttached(Ownership.OWNER, company);
            // the Portable Miner needs HUB Upgrade 1, so only the unlocked company's Workshop takes its parts
            String target = company.equals(YES) ? "siftec:node_scanner" : "siftec:portable_miner";
            workshop.pick(Identifier.parse(target), null);
            Storage<ItemVariant> storage = ItemStorage.SIDED.find(level, at, Direction.UP);
            if (storage == null) {
                report(source, "workshop: no item storage");
                return;
            }
            long sheets, wires, rods, extra;
            try (Transaction t = Transaction.openOuter()) {
                sheets = storage.insert(ItemVariant.of(item("create:iron_sheet")), 64, t);
                wires = storage.insert(ItemVariant.of(item("siftec:wire")), 64, t);
                rods = storage.insert(ItemVariant.of(item("siftec:iron_rod")), 64, t);
                extra = storage.insert(ItemVariant.of(item("minecraft:dirt")), 64, t);
                t.commit();
            }
            workshop.tryBuild();
            long out;
            try (Transaction t = Transaction.openOuter()) {
                out = storage.extract(ItemVariant.of(item(target)), 64, t);
                t.commit();
            }
            report(source, "workshop " + (company.equals(YES) ? "unlocked company building the Node Scanner" : "locked company picking the Portable Miner")
                + ": took " + sheets + " Iron Sheet, " + wires + " Wire, " + rods + " Iron Rod, " + extra + " dirt; funnel took out " + out + " " + target);
            level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
        }
    }

    /** A MAM fed through the transfer API: the parts go to the company's picked node, whose research then starts. */
    private static void mam(CommandSourceStack source, ServerLevel level, BlockPos at, CompanyData data) {
        Company company = new Company();
        company.id = MAM;
        for (Milestone m : Milestones.all()) company.done.add(m.id());
        for (Milestones.Tree tree : Milestones.trees()) for (Milestone m : tree.nodes()) company.done.remove(m.id());
        data.companies().put(company.id, company);
        Milestone node = null;
        for (Milestones.Tree tree : Milestones.trees()) {
            for (Milestone m : tree.nodes()) {
                if (node == null && Milestones.blocker(company, m) == null && m.cost().stream().noneMatch(c -> c.isTag() || !c.present())) node = m;
            }
        }
        if (node == null) {
            report(source, "mam: no node to test");
            return;
        }
        level.setBlockAndUpdate(at, ModBlocks.MAM.get().defaultBlockState());
        BlockEntity mam = level.getBlockEntity(at);
        if (mam == null) {
            report(source, "mam: no block entity");
            return;
        }
        mam.setAttached(Ownership.OWNER, company.id);
        Storage<ItemVariant> storage = ItemStorage.SIDED.find(level, at, Direction.UP);
        long before = 0, taken = 0;
        if (storage != null) {
            try (Transaction t = Transaction.openOuter()) {
                for (Milestone.Cost cost : node.cost()) before += storage.insert(ItemVariant.of(item(cost.key())), cost.count(), t);
                t.abort();
            }
            company.mamPick = node.id();
            try (Transaction t = Transaction.openOuter()) {
                for (Milestone.Cost cost : node.cost()) taken += storage.insert(ItemVariant.of(item(cost.key())), cost.count() + 5, t);
                t.commit();
            }
        }
        int total = node.cost().stream().mapToInt(c -> company.cost(c)).sum();
        report(source, "mam: node " + node.id() + " costs " + total + " parts; taken before it was picked " + before + ", after " + taken
            + "; research now '" + company.research + "'" + (storage == null ? " (no item storage)" : ""));
        level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
    }

    /**
     * A real Create Deployer facing east, holding Power Lines, with a Power Pole two blocks in front of it and another
     * 12 blocks further east; it is made to act once, the way its own cycle does (the protected activate()).
     */
    private static void deployer(CommandSourceStack source, ServerLevel level, BlockPos at) {
        net.minecraft.world.level.block.state.BlockState state = BuiltInRegistries.BLOCK.getValue(Identifier.parse("create:deployer")).defaultBlockState();
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
            state = state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, Direction.EAST);
        }
        BlockPos a = at.east(2), b = at.east(14);
        level.setBlockAndUpdate(at, state);
        level.setBlockAndUpdate(a, ModBlocks.POWER_POLE.get().defaultBlockState());
        level.setBlockAndUpdate(b, ModBlocks.POWER_POLE.get().defaultBlockState());
        String result;
        if (level.getBlockEntity(at) instanceof com.zurrtum.create.content.kinetics.deployer.DeployerBlockEntity deployer) {
            try {
                deployer.initHandler();
                java.lang.reflect.Field field = com.zurrtum.create.content.kinetics.deployer.DeployerBlockEntity.class.getDeclaredField("player");
                field.setAccessible(true);
                com.zurrtum.create.content.kinetics.deployer.DeployerPlayer player = (com.zurrtum.create.content.kinetics.deployer.DeployerPlayer) field.get(deployer);
                player.cast().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item("siftec:power_line"), 3));
                java.lang.reflect.Method activate = com.zurrtum.create.content.kinetics.deployer.DeployerBlockEntity.class.getDeclaredMethod("activate");
                activate.setAccessible(true);
                activate.invoke(deployer);
                String la = level.getBlockEntity(a) instanceof PoleBlockEntity p ? p.lines.toString() : "missing";
                String lb = level.getBlockEntity(b) instanceof PoleBlockEntity p ? p.lines.toString() : "missing";
                result = "lines left in the Deployer " + player.cast().getMainHandItem().getCount() + "; pole in front " + la + ", pole 12 further " + lb;
                player.cast().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            } catch (ReflectiveOperationException | RuntimeException e) {
                result = "could not run: " + e;
            }
        } else {
            result = "no deployer block entity";
        }
        report(source, "deployer: " + result);
        for (BlockPos pos : List.of(a, b)) {
            if (!(level.getBlockEntity(pos) instanceof PoleBlockEntity p)) continue;
            for (BlockPos offset : new ArrayList<>(p.lines)) {
                BlockPos other = pos.offset(offset);
                p.unlink(other);
                if (level.getBlockEntity(other) instanceof PoleBlockEntity q) q.unlink(pos);
            }
        }
        for (BlockPos pos : List.of(at, a, b)) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
    }

    /** Two Power Poles 10 apart and one 40 away; a fake player (what a Deployer uses) holding Power Lines uses one on the first. */
    private static void poles(CommandSourceStack source, ServerLevel level, BlockPos at) {
        BlockPos a = at, b = at.east(10), far = at.east(40);
        for (BlockPos pos : List.of(a, b, far)) level.setBlockAndUpdate(pos, ModBlocks.POWER_POLE.get().defaultBlockState());
        FakePlayer fake = FakePlayer.get(level);
        fake.setPos(a.getX() + 0.5, a.getY() + 1, a.getZ() + 0.5);
        ItemStack lines = new ItemStack(item("siftec:power_line"), 2);
        fake.setItemInHand(InteractionHand.MAIN_HAND, lines);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(a), Direction.UP, a, false);
        List<String> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) results.add(lines.getItem().useOn(new UseOnContext(fake, InteractionHand.MAIN_HAND, hit)).toString());
        String la = level.getBlockEntity(a) instanceof PoleBlockEntity p ? p.lines.toString() : "missing";
        String lb = level.getBlockEntity(b) instanceof PoleBlockEntity p ? p.lines.toString() : "missing";
        String lf = level.getBlockEntity(far) instanceof PoleBlockEntity p ? p.lines.toString() : "missing";
        report(source, "poles: uses " + results + "; lines left in hand " + lines.getCount() + "; first pole " + la + ", pole 10 east " + lb + ", pole 40 east " + lf
            + " (fake player counts as a machine: " + Companies.isFake(fake) + ")");
        fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        for (BlockPos pos : List.of(a, b, far)) {
            if (!(level.getBlockEntity(pos) instanceof PoleBlockEntity p)) continue;
            for (BlockPos offset : new ArrayList<>(p.lines)) {
                BlockPos other = pos.offset(offset);
                p.unlink(other);
                if (level.getBlockEntity(other) instanceof PoleBlockEntity q) q.unlink(pos);
            }
        }
        for (BlockPos pos : List.of(a, b, far)) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
    }
}
