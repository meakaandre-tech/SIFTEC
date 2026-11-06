package com.meakaandre.siftec.command;

import com.meakaandre.siftec.TestReport;
import com.meakaandre.siftec.building.Paint;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.sink.Shop;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.content.decoration.encasing.CasingBlock;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import com.zurrtum.create.content.kinetics.crafter.MechanicalCraftingRecipe;
import com.zurrtum.create.content.kinetics.deployer.ManualApplicationHelper;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code /siftec selftest steel}: SIFTEC's steel in place of Create: Gunsmithing's steel and Create's industrial iron
 * (compat/Replaced.java). Every line prints "SELFTEST steel"; failures say FAIL; the last line counts them.
 * <ul>
 * <li>the Steel Ingot, Sheet and Nugget, the Steel Casing (a Create casing, same id), the steel windows and the
 * steel-encased shaft and cogwheels exist;</li>
 * <li>no loaded recipe, of any mod, takes or makes Gunsmithing's steel or Create's industrial iron, and none of the
 * recipes the pack replaces is loaded; the common steel tags hold SIFTEC's steel and not Gunsmithing's; SIFTEC's steel
 * is made as Gunsmithing's was in the pack (Foundry, pressing, nine nuggets); Gunsmithing's guns take SIFTEC's steel
 * (a Revolver on the Mechanical Crafter, a Spread Barrel in the crafting grid) and not Gunsmithing's;</li>
 * <li>a Steel Ingot on a stripped log or stripped wood makes Steel Casing and takes one ingot; on anything else it does
 * nothing; the Steel Casing encases a shaft, a cogwheel, a large cogwheel and a chute;</li>
 * <li>no replaced item is in a creative tab, all are switched off (which hides them in JEI), and a stray one is
 * converted: in an inventory (a steel block into nine ingots), dropped, placed, and in a chunk as it loads;</li>
 * <li>the Steel Casing's uses still work (the Mechanical Crafter's recipe, the steel casing skin, its milestone), and
 * Paint recolours it as it did industrial iron, leaving the steel windows as built as it did the industrial iron ones.</li>
 * </ul>
 * {@code /siftec worldtest steel setup|aim|made}: the client's screenshots: the steel items, casing and windows on
 * show, then a stripped log turned into Steel Casing by the player's right click.
 */
public final class SteelTests {
    private static final TestReport REPORT = new TestReport("SELFTEST steel ");
    private static final int X = 23000, Y = 200, Z = 23000;
    private static final String COMPANY = "selftest_steel";
    /** What SIFTEC replaces, as it would be written in a recipe file. */
    private static final List<String> OLD = List.of("\"cgs:steel_ingot\"", "\"cgs:steel_sheet\"", "\"cgs:steel_nugget\"", "\"cgs:steel_block\"",
        "\"create:industrial_iron_block\"", "\"create:industrial_iron_window\"", "\"create:industrial_iron_window_pane\"");
    /** The recipes the pack takes out because they made or took the replaced things. */
    private static final List<String> GONE = List.of("cgs:pressing/steel_ingot", "cgs:steel_ingot_from_steel_nugget", "cgs:steel_nugget",
        "cgs:steel_block_from_steel_ingot", "cgs:steel_ingot", "cgs:sequenced_assembly/steel_nail", "cgs:mixing/steel_ingot",
        "create:industrial_iron_block_from_ingots_iron_stonecutting", "create:industrial_iron_window", "create:industrial_iron_window_pane",
        "create:splashing/industrial_iron_block", "create:splashing/industrial_iron_window", "create:splashing/industrial_iron_window_pane",
        "siftec:deploying/steel_casing_from_stripped_logs", "siftec:deploying/steel_casing_from_stripped_woods");

    /** The ids SIFTEC replaces (tools/tables/content.py REPLACED). */
    private static final List<String> OLD_IDS = List.of("cgs:steel_ingot", "cgs:steel_sheet", "cgs:steel_nugget", "cgs:steel_block",
        "create:industrial_iron_block", "create:industrial_iron_window", "create:industrial_iron_window_pane");

    private SteelTests() {
    }

    /**
     * compat.Replaced's converters, called by name so the suite also runs (and fails) on a build from before SIFTEC's
     * steel: -1 when there is no such converter.
     */
    private static int converter(String method, Class<?>[] types, Object... args) {
        try {
            Object got = Class.forName("com.meakaandre.siftec.compat.Replaced").getMethod(method, types).invoke(null, args);
            return got instanceof Integer n ? n : got instanceof Boolean b ? (b ? 1 : 0) : 0;
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    private static boolean old(Item item) {
        return OLD_IDS.contains(id(item));
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("steel").executes(SteelTests::run)))
                .then(Commands.literal("worldtest").then(Commands.literal("steel")
                    .then(Commands.literal("setup").executes(SteelTests::showSetup))
                    .then(Commands.literal("aim").executes(SteelTests::aim))
                    .then(Commands.literal("made").executes(SteelTests::made))))));
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        REPORT.expect(source, what, ok, detail);
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(Items.AIR);
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id)).orElse(Blocks.AIR);
    }

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static String id(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    private static boolean registered(String id) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).isPresent();
    }

    private static boolean inTag(String item, String tag) {
        return new ItemStack(item(item)).is(TagKey.create(Registries.ITEM, Identifier.parse(tag)));
    }

    private static RecipeHolder<?> recipe(MinecraftServer server, String id) {
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) if (holder.id().identifier().toString().equals(id)) return holder;
        return null;
    }

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        REPORT.reset();
        REPORT.attempt(source, "items and blocks", () -> things(source));
        REPORT.attempt(source, "recipes", () -> recipes(source, level.getServer()));
        REPORT.attempt(source, "tags", () -> tags(source));
        REPORT.attempt(source, "guns", () -> guns(source, level));
        REPORT.attempt(source, "the casing", () -> casing(source, level));
        REPORT.attempt(source, "encasing", () -> encasing(source, level));
        REPORT.attempt(source, "hidden", () -> hidden(source, level));
        REPORT.attempt(source, "strays", () -> strays(source, level));
        REPORT.attempt(source, "uses and paint", () -> uses(source, level.getServer()));
        REPORT.report(source, "done: " + REPORT.totals());
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ what exists

    private static void things(CommandSourceStack source) {
        List<String> missing = new ArrayList<>();
        for (String id : List.of("siftec:steel_ingot", "siftec:steel_sheet", "siftec:steel_nugget", "siftec:steel_casing", "siftec:steel_window",
            "siftec:steel_window_pane", "siftec:steel_encased_shaft", "siftec:steel_encased_cogwheel", "siftec:steel_encased_large_cogwheel")) {
            if (!registered(id)) missing.add(id);
        }
        expect(source, "SIFTEC's Steel Ingot, Sheet and Nugget, Steel Casing, steel windows and steel-encased shaft and cogwheels exist",
            missing.isEmpty(), missing.isEmpty() ? "" : "missing " + missing);
        expect(source, "the Steel Casing keeps its id and is a Create casing", block("siftec:steel_casing") instanceof CasingBlock,
            block("siftec:steel_casing").getClass().getSimpleName());
        expect(source, "Gunsmithing's steel and Create's industrial iron are still in the game (the forks are not changed)",
            registered("cgs:steel_ingot") && registered("cgs:steel_sheet") && registered("create:industrial_iron_block"), "");
    }

    // ------------------------------------------------------------------------------------------------ recipes

    private static void recipes(CommandSourceStack source, MinecraftServer server) {
        int read = 0, withoutFile = 0;
        List<String> naming = new ArrayList<>();
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            Identifier id = holder.id().identifier();
            Optional<Resource> file = server.getResourceManager().getResource(Identifier.fromNamespaceAndPath(id.getNamespace(), "recipe/" + id.getPath() + ".json"));
            if (file.isEmpty()) {
                withoutFile++;
                continue;
            }
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = file.get().openAsReader()) {
                reader.lines().forEach(text::append);
            } catch (IOException e) {
                naming.add(id + " (unreadable: " + e + ")");
                continue;
            }
            read++;
            String body = text.toString().replace(" ", "");
            for (String old : OLD) {
                if (body.contains(old)) naming.add(id + " " + old);
            }
        }
        expect(source, "no loaded recipe of any mod takes or makes Gunsmithing's steel or Create's industrial iron",
            read > 500 && naming.isEmpty(), read + " recipe files read (" + withoutFile + " made in code)" + (naming.isEmpty() ? "" : ": " + naming));
        List<String> loaded = new ArrayList<>();
        for (String id : GONE) if (recipe(server, id) != null) loaded.add(id);
        expect(source, "the recipes SIFTEC replaces are not loaded (Gunsmithing's steel, Create's industrial iron, the casing's deploying copies)",
            loaded.isEmpty(), loaded.isEmpty() ? GONE.size() + " recipes out" : "still loaded " + loaded);
        String[][] want = {{"siftec:mixing/foundry/steel_ingot", "siftec:steel_ingot"}, {"siftec:pressing/steel_sheet", "siftec:steel_sheet"},
            {"siftec:crafting/steel_ingot_from_nuggets", "siftec:steel_ingot"}, {"siftec:crafting/steel_nugget", "siftec:steel_nugget"},
            {"siftec:crafting/steel_window", "siftec:steel_window"}, {"siftec:crafting/steel_window_pane", "siftec:steel_window_pane"},
            {"siftec:sequenced_assembly/steel_nail", "cgs:nail_steel"}};
        List<String> wrong = new ArrayList<>();
        for (String[] w : want) {
            RecipeHolder<?> holder = recipe(server, w[0]);
            Optional<Resource> file = server.getResourceManager().getResource(Identifier.fromNamespaceAndPath("siftec", "recipe/" + Identifier.parse(w[0]).getPath() + ".json"));
            String body = "";
            if (file.isPresent()) {
                try (BufferedReader reader = file.get().openAsReader()) {
                    body = String.join("", reader.lines().toList()).replace(" ", "");
                } catch (IOException e) {
                    body = "";
                }
            }
            if (holder == null || !body.contains("\"" + w[1] + "\"")) wrong.add(w[0] + (holder == null ? " not loaded" : " does not make " + w[1]));
        }
        expect(source, "SIFTEC's steel is made as Gunsmithing's was (Foundry ingot, pressed sheet, nine nuggets an ingot and back), its windows from Steel Casing, Gunsmithing's nails on its nugget",
            wrong.isEmpty(), wrong.isEmpty() ? want.length + " recipes" : String.join("; ", wrong));
    }

    private static void tags(CommandSourceStack source) {
        String[][] tags = {{"c:ingots/steel", "steel_ingot"}, {"c:plates/steel", "steel_sheet"}, {"c:nuggets/steel", "steel_nugget"},
            {"c:ingots", "steel_ingot"}, {"c:plates", "steel_sheet"}, {"c:nuggets", "steel_nugget"}};
        List<String> wrong = new ArrayList<>();
        for (String[] t : tags) {
            if (!inTag("siftec:" + t[1], t[0])) wrong.add(t[0] + " lacks siftec:" + t[1]);
            if (inTag("cgs:" + t[1], t[0])) wrong.add(t[0] + " holds cgs:" + t[1]);
        }
        for (String t : List.of("c:storage_blocks/steel", "c:storage_blocks")) if (inTag("cgs:steel_block", t)) wrong.add(t + " holds cgs:steel_block");
        // every item tag: none holds a replaced item
        for (String old : List.of("cgs:steel_ingot", "cgs:steel_sheet", "cgs:steel_nugget", "cgs:steel_block", "create:industrial_iron_block",
            "create:industrial_iron_window", "create:industrial_iron_window_pane")) {
            Item i = item(old);
            if (i == Items.AIR) continue;
            List<String> in = BuiltInRegistries.ITEM.wrapAsHolder(i).tags().map(k -> k.location().toString()).toList();
            if (!in.isEmpty()) wrong.add(old + " in " + in);
        }
        expect(source, "the common steel tags hold SIFTEC's steel, and no tag holds Gunsmithing's steel or Create's industrial iron", wrong.isEmpty(),
            String.join("; ", wrong));
    }

    /** Gunsmithing's guns are made of SIFTEC's steel: by their recipes' tags, on the Mechanical Crafter and in the grid. */
    private static void guns(CommandSourceStack source, ServerLevel level) {
        if (!registered("cgs:revolver")) {
            REPORT.report(source, "guns: Create: Gunsmithing is not installed, nothing to check");
            return;
        }
        RecipeHolder<?> revolver = recipe(level.getServer(), "cgs:mechanical_crafting/revolver");
        boolean steelMade = false, oldMade = false;
        if (revolver != null && revolver.value() instanceof MechanicalCraftingRecipe crafting) {
            steelMade = crafting.matches(revolverGrid("siftec:steel_sheet", "siftec:steel_ingot"), level);
            oldMade = crafting.matches(revolverGrid("cgs:steel_sheet", "cgs:steel_ingot"), level);
        }
        expect(source, "a Gunsmithing Revolver is made on the Mechanical Crafter from SIFTEC's Steel Sheets and Ingots, and not from Gunsmithing's",
            revolver != null && steelMade && !oldMade, revolver == null ? "no revolver recipe" : "SIFTEC steel " + steelMade + ", Gunsmithing steel " + oldMade);
        // the Shotgun's Spread Barrel, in the crafting grid: steel sheet, iron sheet, steel sheet
        CraftingInput siftec = CraftingInput.of(3, 1, List.of(new ItemStack(item("siftec:steel_sheet")), new ItemStack(item("create:iron_sheet")),
            new ItemStack(item("siftec:steel_sheet"))));
        CraftingInput old = CraftingInput.of(3, 1, List.of(new ItemStack(item("cgs:steel_sheet")), new ItemStack(item("create:iron_sheet")),
            new ItemStack(item("cgs:steel_sheet"))));
        var made = level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, siftec, level);
        var notMade = level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, old, level);
        String got = made.map(h -> h.id().identifier().toString()).orElse("nothing");
        expect(source, "a Gunsmithing Spread Barrel is crafted from SIFTEC's Steel Sheets, and not from Gunsmithing's", "cgs:shotgun_spread_barrel".equals(got)
            && notMade.isEmpty(), "SIFTEC steel makes " + got + ", Gunsmithing steel makes " + notMade.map(h -> h.id().identifier().toString()).orElse("nothing"));
        // the ammo: Gunsmithing's piercing rounds and nails take steel nuggets by their tag
        expect(source, "Gunsmithing's rounds and nails take SIFTEC's Steel Nugget (by c:nuggets/steel)", inTag("siftec:steel_nugget", "c:nuggets/steel")
            && recipe(level.getServer(), "cgs:sequenced_assembly/revolver_round_piercing") != null && recipe(level.getServer(), "cgs:nail_steel") != null, "");
    }

    /** The Revolver's grid: BBIC / WALL / "   W" (B steel plate, I steel ingot, C cogwheel, W stripped log, A andesite alloy, L brass plate). */
    private static CraftingInput revolverGrid(String sheet, String ingot) {
        String[] rows = {"BBIC", "WALL", "   W"};
        List<ItemStack> items = new ArrayList<>();
        for (String row : rows) {
            for (char c : row.toCharArray()) {
                items.add(switch (c) {
                    case 'B' -> new ItemStack(item(sheet));
                    case 'I' -> new ItemStack(item(ingot));
                    case 'C' -> new ItemStack(item("create:cogwheel"));
                    case 'W' -> new ItemStack(Items.STRIPPED_OAK_LOG);
                    case 'A' -> new ItemStack(item("create:andesite_alloy"));
                    case 'L' -> new ItemStack(item("create:brass_sheet"));
                    default -> ItemStack.EMPTY;
                });
            }
        }
        return CraftingInput.of(4, 3, items);
    }

    // ------------------------------------------------------------------------------------------------ the casing

    /** The test company, which has Basic Steel Production (the Steel Casing's milestone). */
    private static Company company(MinecraftServer server) {
        CompanyData data = CompanyData.get(server);
        Company c = data.companies().get(COMPANY);
        if (c == null) {
            c = new Company();
            c.id = COMPANY;
            c.name = COMPANY;
            data.companies().put(COMPANY, c);
        }
        c.done.add("basic_steel_production");
        data.setDirty();
        return c;
    }

    private static FakePlayer worker(ServerLevel level) {
        FakePlayer fake = FakePlayer.get(level);
        fake.getInventory().clearContent();
        Company c = company(level.getServer());
        c.members.add(fake.getUUID().toString());
        return fake;
    }

    private static void floor(ServerLevel level) {
        for (int cx = (X - 16) >> 4; cx <= (X + 16) >> 4; cx++)
            for (int cz = (Z - 16) >> 4; cz <= (Z + 16) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = X - 12; x <= X + 12; x++)
            for (int z = Z - 12; z <= Z + 12; z++) {
                level.setBlock(pos.set(x, Y - 1, z), Blocks.CONCRETE.white().defaultBlockState(), Block.UPDATE_ALL);
                for (int y = Y; y < Y + 8; y++) level.setBlock(pos.set(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
    }

    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false);
    }

    /** One Steel Ingot right-clicked onto the block: what the block became and how many ingots are left of four. */
    private static String apply(ServerLevel level, FakePlayer fake, BlockPos pos, BlockState target) {
        level.setBlock(pos, target, Block.UPDATE_ALL);
        ItemStack held = new ItemStack(item("siftec:steel_ingot"), 4);
        fake.setItemInHand(InteractionHand.MAIN_HAND, held);
        fake.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 2.5);
        InteractionResult r = ManualApplicationHelper.manualApplicationRecipesApplyInWorld(level, fake, held, InteractionHand.MAIN_HAND, hit(pos), pos);
        return id(level.getBlockState(pos).getBlock()) + " " + fake.getMainHandItem().getCount() + (r == null ? "" : " " + r);
    }

    private static void casing(CommandSourceStack source, ServerLevel level) {
        floor(level);
        FakePlayer fake = worker(level);
        GameType was = fake.gameMode.getGameModeForPlayer();
        fake.setGameMode(GameType.SURVIVAL);
        try {
            BlockPos at = new BlockPos(X + 8, Y, Z + 8);
            String log = apply(level, fake, at, Blocks.STRIPPED_OAK_LOG.defaultBlockState());
            String wood = apply(level, fake, at, Blocks.STRIPPED_SPRUCE_WOOD.defaultBlockState());
            expect(source, "a Steel Ingot on a stripped log or stripped wood makes Steel Casing and takes one ingot",
                log.startsWith("siftec:steel_casing 3") && wood.startsWith("siftec:steel_casing 3"), "stripped log -> " + log + ", stripped wood -> " + wood);
            String plainLog = apply(level, fake, at, Blocks.OAK_LOG.defaultBlockState());
            String stone = apply(level, fake, at, Blocks.STONE.defaultBlockState());
            String planks = apply(level, fake, at, Blocks.OAK_PLANKS.defaultBlockState());
            expect(source, "a Steel Ingot on anything else (a log with its bark, stone, planks) does nothing and keeps the ingot",
                plainLog.startsWith("minecraft:oak_log 4") && stone.startsWith("minecraft:stone 4") && planks.startsWith("minecraft:oak_planks 4"),
                "log -> " + plainLog + ", stone -> " + stone + ", planks -> " + planks);
            level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        } finally {
            fake.setGameMode(was);
            fake.getInventory().clearContent();
        }
    }

    /** The Steel Casing right-clicked onto a block: what it became. */
    private static BlockState encase(ServerLevel level, FakePlayer fake, BlockPos pos, BlockState target) {
        level.setBlock(pos, target, Block.UPDATE_ALL);
        ItemStack casing = new ItemStack(item("siftec:steel_casing"));
        fake.setItemInHand(InteractionHand.MAIN_HAND, casing);
        fake.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 2.5);
        level.getBlockState(pos).useItemOn(casing, level, fake, InteractionHand.MAIN_HAND, hit(pos));
        return level.getBlockState(pos);
    }

    private static void encasing(CommandSourceStack source, ServerLevel level) {
        FakePlayer fake = worker(level);
        BlockPos at = new BlockPos(X + 10, Y, Z + 8);
        StringBuilder said = new StringBuilder();
        boolean ok = true;
        String[][] pairs = {{"create:shaft", "siftec:steel_encased_shaft"}, {"create:cogwheel", "siftec:steel_encased_cogwheel"},
            {"create:large_cogwheel", "siftec:steel_encased_large_cogwheel"}};
        for (String[] p : pairs) {
            BlockState before = block(p[0]).defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X);
            BlockState after = encase(level, fake, at, before);
            boolean right = id(after.getBlock()).equals(p[1]) && after.getValue(BlockStateProperties.AXIS) == Direction.Axis.X
                && level.getBlockEntity(at) instanceof KineticBlockEntity;
            ok &= right;
            said.append(p[0]).append(" -> ").append(id(after.getBlock())).append(level.getBlockEntity(at) instanceof KineticBlockEntity ? "" : " (no kinetic block entity)").append("; ");
            level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        expect(source, "the Steel Casing encases a shaft, a cogwheel and a large cogwheel as Create's casings do, on Create's own block entities", ok,
            said.toString().trim());
        BlockState chute = encase(level, fake, at, block("create:chute").defaultBlockState());
        String shape = chute.getBlock() == block("create:chute") ? chute.getProperties().stream().filter(p -> p.getName().equals("shape"))
            .map(p -> chute.getValue(p).toString()).findFirst().orElse("?") : id(chute.getBlock());
        expect(source, "the Steel Casing encases a chute, as Create's industrial iron did", "encased".equalsIgnoreCase(shape), "chute shape " + shape);
        level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        fake.getInventory().clearContent();
    }

    // ------------------------------------------------------------------------------------------------ out of sight

    private static void hidden(CommandSourceStack source, ServerLevel level) {
        Set<String> off = new TreeSet<>();
        for (Identifier id : Milestones.disabled()) off.add(id.toString());
        List<String> notOff = new ArrayList<>();
        for (String id : OLD_IDS) if (!off.contains(id)) notOff.add(id);
        expect(source, "every replaced item is switched off, which is also what keeps it out of JEI", notOff.isEmpty(),
            OLD_IDS.size() + " replaced" + (notOff.isEmpty() ? "" : ", not off " + notOff));
        CreativeModeTabs.tryRebuildTabContents(level.enabledFeatures(), true, level.registryAccess());
        List<String> shown = new ArrayList<>();
        boolean steelShown = false;
        for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
            for (ItemStack s : tab.getDisplayItems()) {
                if (old(s.getItem())) shown.add(id(s.getItem()) + " in " + tab.getDisplayName().getString());
                if (s.is(item("siftec:steel_ingot"))) steelShown = true;
            }
            for (ItemStack s : tab.getSearchTabDisplayItems()) if (old(s.getItem())) shown.add(id(s.getItem()) + " in search");
        }
        expect(source, "no creative tab (nor the search) lists Gunsmithing's steel or Create's industrial iron, and SIFTEC's Steel Ingot is listed",
            shown.isEmpty() && steelShown, shown.isEmpty() ? "steel ingot listed " + steelShown : String.join(", ", shown));
    }

    private static int count(FakePlayer fake, String id) {
        int n = 0;
        for (int i = 0; i < fake.getInventory().getContainerSize(); i++) {
            ItemStack s = fake.getInventory().getItem(i);
            if (s.is(item(id))) n += s.getCount();
        }
        return n;
    }

    private static void strays(CommandSourceStack source, ServerLevel level) {
        // in an inventory
        FakePlayer fake = worker(level);
        fake.getInventory().setItem(0, new ItemStack(item("cgs:steel_block"), 10));
        fake.getInventory().setItem(1, new ItemStack(item("cgs:steel_sheet"), 5));
        fake.getInventory().setItem(2, new ItemStack(item("cgs:steel_nugget"), 7));
        fake.getInventory().setItem(3, new ItemStack(item("create:industrial_iron_block"), 3));
        fake.getInventory().setItem(4, new ItemStack(item("create:industrial_iron_window_pane"), 12));
        fake.getInventory().setItem(5, new ItemStack(item("cgs:steel_ingot"), 2));
        int converted = converter("convert", new Class<?>[]{ServerPlayer.class}, fake);
        String got = "ingots " + count(fake, "siftec:steel_ingot") + ", sheets " + count(fake, "siftec:steel_sheet") + ", nuggets " + count(fake, "siftec:steel_nugget")
            + ", casing " + count(fake, "siftec:steel_casing") + ", panes " + count(fake, "siftec:steel_window_pane") + ", stacks changed " + converted;
        int left = 0;
        for (String old : List.of("cgs:steel_block", "cgs:steel_sheet", "cgs:steel_nugget", "cgs:steel_ingot", "create:industrial_iron_block",
            "create:industrial_iron_window_pane")) left += count(fake, old);
        expect(source, "Gunsmithing steel and Create industrial iron in an inventory become SIFTEC's (ten steel blocks are 90 ingots)",
            count(fake, "siftec:steel_ingot") == 92 && count(fake, "siftec:steel_sheet") == 5 && count(fake, "siftec:steel_nugget") == 7
                && count(fake, "siftec:steel_casing") == 3 && count(fake, "siftec:steel_window_pane") == 12 && left == 0, got + ", old left " + left);
        fake.getInventory().clearContent();

        // dropped on the ground
        ItemEntity drop = new ItemEntity(level, X + 0.5, Y + 1, Z + 10.5, new ItemStack(item("cgs:steel_sheet"), 3));
        level.addFreshEntity(drop);
        String dropped = id(drop.getItem().getItem()) + " x" + drop.getItem().getCount();
        drop.discard();
        expect(source, "a dropped Gunsmithing Steel Sheet becomes SIFTEC's", "siftec:steel_sheet x3".equals(dropped), dropped);

        // placed
        BlockPos at = new BlockPos(X - 8, Y, Z + 8);
        level.setBlock(at, block("create:industrial_iron_block").defaultBlockState(), Block.UPDATE_ALL);
        String placed = id(level.getBlockState(at).getBlock());
        BlockState pane = block("create:industrial_iron_window_pane").defaultBlockState().setValue(BlockStateProperties.NORTH, true);
        level.setBlock(at.east(), pane, Block.UPDATE_CLIENTS);
        String placedPane = id(level.getBlockState(at.east()).getBlock());
        expect(source, "Create industrial iron placed in the world (by a command, a mod, a structure) is SIFTEC's steel instead",
            "siftec:steel_casing".equals(placed) && "siftec:steel_window_pane".equals(placedPane), placed + ", " + placedPane);

        // in a chunk as it was saved: the old block written straight into the chunk, then the chunk looked through
        LevelChunk chunk = level.getChunkAt(at);
        BlockPos old = at.above(2);
        int section = level.getSectionIndex(old.getY());
        chunk.getSections()[section].setBlockState(old.getX() & 15, old.getY() & 15, old.getZ() & 15, block("create:industrial_iron_window").defaultBlockState());
        boolean planted = level.getBlockState(old).getBlock() == block("create:industrial_iron_window");
        int swapped = converter("convert", new Class<?>[]{ServerLevel.class, LevelChunk.class}, level, chunk);
        String after = id(level.getBlockState(old).getBlock());
        expect(source, "a chunk that still holds Create industrial iron has it swapped for SIFTEC's steel", planted && swapped >= 1 && "siftec:steel_window".equals(after),
            "planted " + planted + ", swapped " + swapped + ", now " + after);
        for (BlockPos p : List.of(at, at.east(), old)) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    // ------------------------------------------------------------------------------------------------ the casing's uses

    private static void uses(CommandSourceStack source, MinecraftServer server) {
        RecipeHolder<?> crafter = recipe(server, "siftec:crafting/mechanical_crafter");
        Optional<Resource> file = server.getResourceManager().getResource(Identifier.fromNamespaceAndPath("siftec", "recipe/crafting/mechanical_crafter.json"));
        boolean takesCasing = false;
        if (file.isPresent()) {
            try (BufferedReader reader = file.get().openAsReader()) {
                takesCasing = String.join("", reader.lines().toList()).replace(" ", "").contains("\"siftec:steel_casing\"");
            } catch (IOException e) {
                takesCasing = false;
            }
        }
        Milestone steel = null;
        for (Milestone m : Milestones.all()) if (m.id().equals("basic_steel_production")) steel = m;
        boolean unlocks = steel != null && steel.items().stream().map(Identifier::toString).toList()
            .containsAll(List.of("siftec:steel_ingot", "siftec:steel_sheet", "siftec:steel_nugget", "siftec:steel_casing", "siftec:steel_window"));
        String skin = Shop.skins().get("steel"), unpainted = Shop.finishes().get("unpainted");
        expect(source, "the Steel Casing's uses still work: the Mechanical Crafter takes it, the steel casing skin and the unpainted finish are it, Basic Steel Production unlocks the steel",
            crafter != null && takesCasing && "siftec:steel_casing".equals(skin) && "siftec:steel_casing".equals(unpainted) && unlocks,
            "crafter " + (crafter != null) + "/" + takesCasing + ", skin " + skin + ", unpainted " + unpainted + ", milestone " + unlocks);
        Paint paint = new Paint("blue", "red", "painted", "brass");
        BlockState casing = block("siftec:steel_casing").defaultBlockState(), window = block("siftec:steel_window").defaultBlockState(),
            pane = block("siftec:steel_window_pane").defaultBlockState();
        expect(source, "Paint recolours the Steel Casing to the secondary colour as it did industrial iron, and leaves the steel windows as built (as it did industrial iron's)",
            paint.look(casing, false).is(Blocks.CONCRETE.red()) && Paint.NONE.look(casing, false).is(casing.getBlock())
                && paint.look(window, false).is(window.getBlock()) && paint.look(pane, false).is(pane.getBlock()),
            "casing -> " + id(paint.look(casing, false).getBlock()) + ", window -> " + id(paint.look(window, false).getBlock()));
    }

    // ------------------------------------------------------------------------------------------------ the client's pictures

    /** The steel on show: its items in frames on a wall, its blocks in a row in front, and a stripped log to case. */
    private static int showSetup(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        floor(level);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = X - 5; x <= X + 5; x++)
            for (int y = Y; y <= Y + 4; y++) level.setBlock(pos.set(x, y, Z - 3), Blocks.CONCRETE.lightGray().defaultBlockState(), Block.UPDATE_ALL);
        String[] row = {"siftec:steel_casing", "siftec:steel_window", "siftec:steel_window_pane", "siftec:steel_encased_shaft",
            "siftec:steel_encased_cogwheel", "siftec:steel_encased_large_cogwheel"};
        for (int i = 0; i < row.length; i++) level.setBlock(pos.set(X - 4 + i, Y, Z - 2), block(row[i]).defaultBlockState(), Block.UPDATE_ALL);
        // a second casing and window on top, so the casing's top and the window joining up show
        level.setBlock(pos.set(X - 4, Y + 1, Z - 2), block("siftec:steel_casing").defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos.set(X - 3, Y + 1, Z - 2), block("siftec:steel_window").defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos.set(X + 3, Y, Z - 2), Blocks.STRIPPED_OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        // the three items, framed at eye height on the wall
        for (net.minecraft.world.entity.Entity e : level.getEntitiesOfClass(ItemFrame.class, new net.minecraft.world.phys.AABB(X - 6, Y, Z - 4, X + 6, Y + 5, Z))) e.discard();
        String[] items = {"siftec:steel_ingot", "siftec:steel_sheet", "siftec:steel_nugget"};
        int framed = 0;
        for (int i = 0; i < items.length; i++) {
            ItemFrame frame = new ItemFrame(level, new BlockPos(X - 1 + i, Y + 2, Z - 2), Direction.SOUTH);
            frame.setItem(new ItemStack(item(items[i])));
            if (level.addFreshEntity(frame)) framed++;
        }
        REPORT.reset();
        expect(source, "showroom: the steel blocks and the three steel items are on show", framed == 3
            && level.getBlockState(pos.set(X - 4, Y, Z - 2)).is(block("siftec:steel_casing")), framed + " frames");
        REPORT.report(source, "showroom done: " + REPORT.totals());
        return 1;
    }

    /** The player in front of the stripped log, looking at it, holding the Steel Ingots. */
    private static int aim(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        var players = source.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) return 0;
        ServerPlayer player = players.get(0);
        player.getInventory().setItem(player.getInventory().getSelectedSlot(), new ItemStack(item("siftec:steel_ingot"), 16));
        // the log's middle is 2 blocks ahead and 1.1 below the eyes: 29 degrees down
        player.teleportTo(source.getServer().overworld(), X + 3.5, Y, Z + 0.5, Set.of(), 180, 29, true);
        REPORT.report(source, "aim: at the stripped log, holding " + player.getMainHandItem().getCount() + " " + id(player.getMainHandItem().getItem()));
        return 1;
    }

    /** After the player's right click: the stripped log is Steel Casing. */
    private static int made(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        REPORT.reset();
        String now = id(level.getBlockState(new BlockPos(X + 3, Y, Z - 2)).getBlock());
        expect(source, "client: the player's right click with a Steel Ingot turned the stripped log into Steel Casing", "siftec:steel_casing".equals(now), now);
        REPORT.report(source, "client done: " + REPORT.totals());
        return 1;
    }
}
