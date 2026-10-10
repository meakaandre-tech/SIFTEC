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
import com.zurrtum.create.content.kinetics.crafter.MechanicalCraftingRecipe;
import com.zurrtum.create.content.kinetics.deployer.ItemApplicationInput;
import com.zurrtum.create.content.kinetics.deployer.ItemApplicationRecipe;
import com.zurrtum.create.content.processing.sequenced.SequencedAssemblyRecipe;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.AllRecipeTypes;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
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
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
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
 * for a company that has them unlocked; a big build's sub-assembly and the build itself run step by step through a
 * sequenced assembly (as the Deployer asks for each step) and through the Mechanical Crafter, for an unlocked and a
 * locked company; a Workshop saved by the old automatic mode giving its parts back; and a machine's fake player and
 * a real Deployer stringing a Power Line between two poles. Every line of output starts with "SELFTEST automate".
 */
public final class AutomateTests {
    private static final String YES = "selftest_auto_yes", NO = "selftest_auto_no";

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
            assembly(source, level, at.east(4), "siftec:gateway_coil");
            assembly(source, level, at.east(4), "siftec:wormhole_gateway");
            // a shortened line: three Deployers (Reinforced Iron Plate, Wire Spool, Screw Bundle), two loops
            assembly(source, level, at.east(4), "siftec:object_scanner");
            crafted(source, level, at.east(4), "siftec:gas_mask");
            crafted(source, level, at.east(4), "siftec:accelerator_segment");
            crafted(source, level, at.east(4), "siftec:particle_accelerator");
            workshop(source, level, at.west(4));
            poles(source, level, at.south(4));
            deployer(source, level, at.north(6));
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
                    var found = level.recipeAccess().getRecipeFor(com.zurrtum.create.AllRecipeTypes.DEPLOYING, input, level);
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

    /** The Mechanical Crafter recipe that makes the item, its grid laid out as the crafters hold it. */
    private static void crafted(CommandSourceStack source, ServerLevel level, BlockPos at, String made) {
        MechanicalCraftingRecipe recipe = null;
        for (RecipeHolder<?> holder : source.getServer().getRecipeManager().getRecipes()) {
            if (holder.value() instanceof MechanicalCraftingRecipe r && holder.id().identifier().getNamespace().equals("siftec")
                && BuiltInRegistries.ITEM.getKey(r.result().item().value()).toString().equals(made)) recipe = r;
        }
        if (recipe == null) {
            report(source, "crafter " + made + ": no Mechanical Crafter recipe makes it");
            return;
        }
        List<ItemStack> cells = new ArrayList<>();
        int parts = 0;
        for (java.util.Optional<Ingredient> cell : recipe.raw().ingredients()) {
            ItemStack stack = cell.map(AutomateTests::first).orElse(ItemStack.EMPTY);
            if (!stack.isEmpty()) parts++;
            cells.add(stack);
        }
        CraftingInput grid = CraftingInput.of(recipe.raw().width(), recipe.raw().height(), cells);
        level.setBlockAndUpdate(at, BuiltInRegistries.BLOCK.getValue(Identifier.parse("create:mechanical_crafter")).defaultBlockState());
        BlockEntity crafter = level.getBlockEntity(at);
        if (crafter == null) {
            report(source, "crafter: no block entity");
            return;
        }
        StringBuilder said = new StringBuilder();
        for (String company : List.of(YES, NO)) {
            crafter.setAttached(Ownership.OWNER, company);
            String result;
            Ownership.ticking(crafter);
            try {
                result = level.recipeAccess().getRecipeFor(com.zurrtum.create.AllRecipeTypes.MECHANICAL_CRAFTING, grid, level)
                    .map(h -> h.value().assemble(grid).toString()).orElse("refused");
            } finally {
                Ownership.ticking(null);
            }
            said.append(company.equals(YES) ? "unlocked company: " : "; locked company: ").append(result);
        }
        report(source, "crafter " + made + " (" + recipe.raw().width() + " by " + recipe.raw().height() + ", " + parts + " parts): " + said);
        level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
    }

    /**
     * A Workshop loaded from what the old automatic mode saved (4 Wire held, a Node Scanner in the output slot): it
     * takes nothing from belts any more, and gives what it held to the player who opens it.
     */
    private static void workshop(CommandSourceStack source, ServerLevel level, BlockPos at) {
        level.setBlockAndUpdate(at, ModBlocks.EQUIPMENT_WORKSHOP.get().defaultBlockState());
        if (!(level.getBlockEntity(at) instanceof WorkshopBlockEntity workshop)) {
            report(source, "workshop: no block entity");
            return;
        }
        net.minecraft.nbt.CompoundTag saved = new net.minecraft.nbt.CompoundTag();
        net.minecraft.nbt.CompoundTag held = new net.minecraft.nbt.CompoundTag();
        held.putInt("siftec:wire", 4);
        saved.put("Held", held);
        saved.putString("Target", "siftec:node_scanner");
        saved.put("Output", ItemStack.OPTIONAL_CODEC.encodeStart(level.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),
            new ItemStack(item("siftec:node_scanner"))).getOrThrow());
        workshop.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), saved));
        int loaded = workshop.leftover();
        boolean storage = net.fabricmc.fabric.api.transfer.v1.item.ItemStorage.SIDED.find(level, at, Direction.UP) != null;
        FakePlayer fake = FakePlayer.get(level);
        fake.getInventory().clearContent();
        boolean gave = workshop.giveBack(fake);
        int wire = fake.getInventory().countItem(item("siftec:wire")), scanners = fake.getInventory().countItem(item("siftec:node_scanner"));
        fake.getInventory().clearContent();
        report(source, "workshop: loaded " + loaded + " items from the old save; takes items from belts: " + storage + "; gave back " + gave
            + " (" + wire + " Wire, " + scanners + " Node Scanner); left " + workshop.leftover());
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
