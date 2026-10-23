package com.meakaandre.siftec.command;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.TestReport;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.power.PowerTests;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.AllRecipeTypes;
import com.zurrtum.create.content.kinetics.deployer.ItemApplicationInput;
import com.zurrtum.create.content.kinetics.deployer.ItemApplicationRecipe;
import com.zurrtum.create.content.processing.sequenced.SequencedAssemblyRecipe;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.antarcticgardens.cna.content.electricity.network.ElectricalNetwork;

import java.util.ArrayList;
import java.util.List;

/**
 * Selftests for automation: the grid's copper wire pressed from Cable (and the Workshop crafter route refused); the
 * Manufacturer's second step for a part and for equipment, run step by step through a sequenced assembly (as the
 * Deployer asks for each step), for an unlocked and a locked company; and a machine's fake player stringing New Age
 * wire between two Power Poles. Every line of output starts with "SELFTEST automate".
 */
public final class AutomateTests {
    private static final String YES = "selftest_auto_yes", NO = "selftest_auto_no";

    private static final TestReport REPORT = new TestReport("SELFTEST automate ");

    private AutomateTests() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("worldtest").then(Commands.literal("automate").executes(AutomateTests::run)))));
    }

    private static void report(CommandSourceStack source, String text) {
        REPORT.report(source, text);
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
            // the Manufacturer's second step: its Core takes one Deployer step per kind of part
            assembly(source, level, at.east(4), "siftec:adaptive_control_unit");
            assembly(source, level, at.east(4), "siftec:modular_engine");
            // equipment made by machine the same way (Reinforced Iron Plate, Wire, Screw; then Fabric, Copper Sheet, Steel Pipe)
            assembly(source, level, at.east(4), "siftec:object_scanner");
            assembly(source, level, at.east(4), "siftec:gas_mask");
            poles(source, level, new BlockPos(x, 250, z + 6));
        } catch (RuntimeException e) {
            report(source, "FAILED with " + e);
            Siftec.LOGGER.error("automate selftest", e);
        } finally {
            level.setChunkForced(x >> 4, z >> 4, false);
            data.companies().remove(YES);
            data.companies().remove(NO);
        }
        return 1;
    }

    /** A Mechanical Press asking for its recipe as it does while it ticks, and a Mechanical Crafter that no recipe of the pack's uses. */
    private static void machines(CommandSourceStack source, ServerLevel level, BlockPos at) {
        level.setBlockAndUpdate(at, BuiltInRegistries.BLOCK.getValue(Identifier.parse("create:mechanical_press")).defaultBlockState());
        level.setBlockAndUpdate(at.east(), BuiltInRegistries.BLOCK.getValue(Identifier.parse("create:mechanical_crafter")).defaultBlockState());
        BlockEntity press = level.getBlockEntity(at), crafter = level.getBlockEntity(at.east());
        if (press == null || crafter == null) {
            report(source, "machines: press or crafter has no block entity");
            return;
        }
        SingleRecipeInput cable = new SingleRecipeInput(new ItemStack(item("siftec:cable")));
        // the Workshop crafter route to a Portable Miner (1 Mechanical Drill, 2 Iron Sheet, 4 Iron Rod) is refused
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

    private static ItemStack first(Ingredient ingredient) {
        return ingredient.items().findFirst().map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    /**
     * Runs the sequenced assembly that makes the item, one step after another, asking for each step's recipe the way a
     * Deployer on a belt does (its held item and the item in front of it) while that Deployer ticks for the company.
     */
    private static void assembly(CommandSourceStack source, ServerLevel level, BlockPos at, String made) {
        SequencedAssemblyRecipe line = null;
        for (RecipeHolder<?> holder : source.getServer().getRecipeManager().getRecipes()) {
            if (holder.value() instanceof SequencedAssemblyRecipe r && BuiltInRegistries.ITEM.getKey(r.result().item().value()).toString().equals(made)) line = r;
        }
        if (line == null) {
            report(source, "assembly " + made + ": no sequenced assembly makes it");
            return;
        }
        level.setBlockAndUpdate(at, BuiltInRegistries.BLOCK.getValue(Identifier.parse("create:deployer")).defaultBlockState());
        BlockEntity deployer = level.getBlockEntity(at);
        if (deployer == null) {
            report(source, "assembly: the deployer has no block entity");
            return;
        }
        StringBuilder said = new StringBuilder();
        for (String company : List.of(YES, NO)) {
            deployer.setAttached(Ownership.OWNER, company);
            ItemStack stack = first(line.ingredient());
            int used = 1, step = 0;
            String outcome = null;
            Ownership.ticking(deployer);
            try {
                for (Recipe<?> recipe : line.sequence()) {
                    step++;
                    if (!(recipe instanceof ItemApplicationRecipe application)) {
                        outcome = "step " + step + " is not a Deployer step";
                        break;
                    }
                    ItemApplicationInput input = new ItemApplicationInput(stack, first(application.ingredient()));
                    var found = level.recipeAccess().getRecipeFor(AllRecipeTypes.DEPLOYING, input, level);
                    if (found.isEmpty()) {
                        outcome = "refused at step " + step + " of " + line.sequence().size();
                        break;
                    }
                    List<ItemStack> out = found.get().value().assemble(input, level.getRandom());
                    stack = out.isEmpty() ? ItemStack.EMPTY : out.get(0);
                    used++;
                }
            } finally {
                Ownership.ticking(null);
            }
            if (outcome == null) outcome = stack + " after " + line.sequence().size() + " steps from " + used + " parts";
            said.append(company.equals(YES) ? "unlocked company: " : "; locked company: ").append(outcome);
        }
        report(source, "assembly " + made + ": " + said);
        level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
    }

    /**
     * Wires strung by a machine: a fake player (what a Deployer uses) holding New Age copper wire uses it on a Power
     * Pole Mk.1's connector and then on a second pole's 10 blocks east (joined), and on one 40 blocks east of that (too
     * long: a wire between two buildings reaches 32 blocks). Also the copper wire pressed from Cable.
     */
    private static void poles(CommandSourceStack source, ServerLevel level, BlockPos at) {
        BlockPos a = at, b = at.east(10), far = at.east(50);
        for (BlockPos p : List.of(a, b, far)) level.getChunk(p.getX() >> 4, p.getZ() >> 4);
        var pa = PowerTests.build(level, source, "power_pole_mk1", a, Direction.SOUTH);
        var pb = PowerTests.build(level, source, "power_pole_mk1", b, Direction.SOUTH);
        var pf = PowerTests.build(level, source, "power_pole_mk1", far, Direction.SOUTH);
        var ca = PowerTests.connector(level, pa);
        var cb = PowerTests.connector(level, pb);
        var cf = PowerTests.connector(level, pf);
        if (ca == null || cb == null || cf == null) {
            report(source, "poles: could not put the poles down");
            return;
        }
        for (var c : List.of(ca, cb, cf)) if (c.getNetwork() == null) c.setNetwork(new ElectricalNetwork(c));
        FakePlayer fake = FakePlayer.get(level);
        fake.setPos(a.getX() + 0.5, a.getY() + 1, a.getZ() + 0.5);
        ItemStack wires = new ItemStack(item("create_new_age:copper_wire"), 4);
        fake.setItemInHand(InteractionHand.MAIN_HAND, wires);
        List<String> results = new ArrayList<>();
        for (BlockPos target : List.of(ca.getBlockPos(), cb.getBlockPos(), cb.getBlockPos(), cf.getBlockPos())) {
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false);
            results.add(wires.getItem().useOn(new UseOnContext(fake, InteractionHand.MAIN_HAND, hit)).toString());
        }
        report(source, "poles: uses " + results + "; wire left in hand " + wires.getCount() + "; first pole joined to the second " + ca.isConnected(cb.getBlockPos())
            + ", the second to the far one (50 blocks) " + cb.isConnected(cf.getBlockPos()) + " (fake player counts as a machine: " + Companies.isFake(fake) + ")");
        fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        for (var c : List.of(pa, pb, pf)) if (c != null) Buildings.dismantle(level, c);
        // the wire itself: one Cable pressed (1.0's Power Line is 1 Cable), only for a company that has it unlocked
        boolean pressed = false;
        for (RecipeHolder<?> holder : source.getServer().getRecipeManager().getRecipes()) {
            if (holder.id().identifier().toString().equals("siftec:pressing/copper_wire")) pressed = true;
        }
        report(source, "poles: the copper wire is pressed from Cable: " + pressed);
    }
}
