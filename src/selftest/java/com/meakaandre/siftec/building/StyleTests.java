package com.meakaandre.siftec.building;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.TestReport;
import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.node.NodeLook;
import com.meakaandre.siftec.node.NodeSites;
import com.meakaandre.siftec.node.NodeType;
import com.meakaandre.siftec.node.Purity;
import com.meakaandre.siftec.prefab.PrefabTests;
import com.meakaandre.siftec.prefab.Template;
import com.meakaandre.siftec.prefab.Templates;
import com.meakaandre.siftec.registry.ModBlocks;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The buildings' style (design/building-style.md): checks that every building template reads as drawn, and the client
 * showroom the screenshots of each building are taken in.
 * <ul>
 * <li>{@code selftest style}: every block in every building template is a real block with every property it names
 * applied (a typo would quietly turn into air or a default state), every building carries the style's materials
 * (orange, dark industrial iron and half-block trim), and the HUB's bevels read as stairs.</li>
 * <li>{@code worldtest style setup}: every Build Gun building (not the vehicles) and the MAM put down on a white floor,
 * two rows of them facing south, the miners on nodes, the extractors on their wells, the Water Extractor in a pool and
 * the Geothermal Generator over a geyser.</li>
 * <li>{@code worldtest style look <id>}: the camera on one of them from the front right and above, as Polartt's
 * reference shots look at his buildings; {@code hub} and {@code wormhole_gateway} look at the HUB and Gateway the
 * {@code sathub} stages put up.</li>
 * </ul>
 */
public final class StyleTests {
    private static final TestReport REPORT = new TestReport("SELFTEST style ");
    private static final TestReport SHOWROOM = new TestReport("SELFTEST style showroom ");
    private static final int SX = 3000, SY = 181, SZ = 3000, GAP = 12, ROW_GAP = 70, PER_ROW = 24;
    /** Where each showroom building stands: its anchor (the middle of its front edge), width, depth and height. */
    private static final Map<String, int[]> PLACED = new LinkedHashMap<>();

    private StyleTests() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("style").executes(StyleTests::run)))
                .then(Commands.literal("worldtest").then(Commands.literal("style")
                    .then(Commands.literal("setup").executes(StyleTests::setup))
                    .then(Commands.literal("look").then(Commands.argument("id", StringArgumentType.word())
                        .executes(c -> look(c, StringArgumentType.getString(c, "id")))))))));
    }

    /** The Build Gun buildings the showroom puts down, in order: everything but the HUB, the Gateway and the vehicles. */
    public static List<BuildingDef> shown() {
        List<BuildingDef> out = new ArrayList<>();
        for (BuildingDef def : Buildings.all()) {
            if (def.dev() || def.structure() || def.vehicle()) continue;
            out.add(def);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ the checks

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        REPORT.reset();
        BuildingTemplate.forget();
        for (BuildingDef def : shown()) {
            // the palette as written in the file, against the blocks the game knows
            List<String> bad = new ArrayList<>();
            int entries = 0;
            try (InputStream in = Siftec.class.getResourceAsStream(def.templatePath())) {
                if (in == null) {
                    bad.add("no file");
                } else {
                    CompoundTag tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
                    ListTag palette = tag.getListOrEmpty("palette");
                    entries = palette.size();
                    for (int i = 0; i < palette.size(); i++) {
                        String problem = problem(palette.getCompoundOrEmpty(i));
                        if (problem != null) bad.add(problem);
                    }
                }
            } catch (IOException | RuntimeException e) {
                bad.add(e.toString());
            }
            REPORT.expect(source, def.id() + ": every block in its template is a real block with its properties", bad.isEmpty() && entries > 0,
                bad.isEmpty() ? entries + " palette entries" : String.join("; ", bad));

            // the style's materials (not the Foundations tab's pieces, the poles or the smallest rigs)
            BuildingTemplate t = BuildingTemplate.of(def);
            if (t == null || def.logic() == Logic.FOUNDATION || def.power() == PowerRole.POLE || t.members.size() < 30) continue;
            Set<String> blocks = new TreeSet<>();
            for (BuildingTemplate.Cell cell : t.members) blocks.add(BuiltInRegistries.BLOCK.getKey(cell.state().getBlock()).toString());
            boolean orange = blocks.contains("minecraft:orange_concrete"), dark = blocks.contains("create:industrial_iron_block");
            boolean half = blocks.stream().anyMatch(b -> b.endsWith("_stairs") || b.endsWith("_slab") || b.endsWith("_trapdoor"));
            REPORT.expect(source, def.id() + ": built in the style's orange, dark industrial iron and half-block trim", orange && dark && half,
                "orange " + orange + ", industrial iron " + dark + ", half blocks " + half);
        }
        // the HUB's roof bevels are dark stairs at every level from the start
        Template hub = Templates.hub();
        int stairs = 0;
        for (long key : hub.keys()) {
            BlockState at = hub.at(key, 0);
            if (at != null && BuiltInRegistries.BLOCK.getKey(at.getBlock()).toString().equals("create:polished_cut_deepslate_stairs")) stairs++;
        }
        REPORT.expect(source, "HUB: its roof's edge is bevelled in dark stairs", stairs > 20, stairs + " stairs at level 0");
        REPORT.report(source, "result: " + REPORT.totals());
        return 1;
    }

    /** What is wrong with one palette entry, or null: an unknown block, or a property it does not have or cannot take. */
    private static String problem(CompoundTag entry) {
        String id = entry.contains("id") ? entry.getStringOr("id", "") : entry.getStringOr("Name", "");
        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id));
        if (block.isEmpty()) return "unknown block " + id;
        CompoundTag props = entry.contains("properties") ? entry.getCompoundOrEmpty("properties") : entry.getCompoundOrEmpty("Properties");
        BlockState state = block.get().defaultBlockState();
        for (String name : props.keySet()) {
            Property<?> property = state.getBlock().getStateDefinition().getProperty(name);
            String value = props.getStringOr(name, "");
            if (property == null) return id + " has no property " + name;
            if (property.getValue(value).isEmpty()) return id + "." + name + " cannot be " + value;
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------ the showroom

    private static int setup(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        SHOWROOM.reset();
        BuildingTemplate.forget();
        PLACED.clear();
        // where each one goes: two rows facing south (the camera's side), a gap between neighbours
        List<BuildingDef> defs = shown();
        Map<String, BlockPos> anchors = new LinkedHashMap<>();
        int x = SX, row = 0, n = 0, maxX = SX;
        for (BuildingDef def : defs) {
            BuildingTemplate t = BuildingTemplate.of(def);
            if (t == null) continue;
            if (n == PER_ROW) {
                row++;
                n = 0;
                x = SX;
            }
            x -= t.minX;
            anchors.put(def.id(), new BlockPos(x, SY, SZ - row * ROW_GAP));
            PLACED.put(def.id(), new int[]{x, SY, SZ - row * ROW_GAP, t.maxX - t.minX + 1, t.depth, t.height});
            x += t.maxX + 1 + GAP;
            maxX = Math.max(maxX, x);
            n++;
        }
        // the MAM: one block, on a little pad at the end of the last row
        BlockPos mam = new BlockPos(x + 2, SY, SZ - row * ROW_GAP);
        PLACED.put("mam", new int[]{mam.getX(), SY, mam.getZ(), 1, 1, 1});
        maxX = Math.max(maxX, x + 6);
        floor(level, SX - 12, SZ - row * ROW_GAP - 60, maxX + 12, SZ + 40);

        int built = 0;
        for (BuildingDef def : defs) {
            BlockPos at = anchors.get(def.id());
            if (at == null) continue;
            Placement p = new Placement(def, at, Direction.NORTH, false);
            if (def.node()) {
                int[] c = def.nodeCell();
                BlockPos cell = p.at(c[0], c[1], c[2]);
                NodeType type = def.fluidNode().equals("oil") ? NodeType.OIL : def.fluidNode().equals("nitrogen") ? NodeType.NITROGEN : NodeType.IRON;
                node(level, type, cell);
            }
            if (def.intake() != null) {
                // a pool under the Water Extractor, in a rim so it stays in
                for (int dx = -4; dx <= 4; dx++)
                    for (int dz = -1; dz <= 7; dz++) level.setBlock(p.at(dx, 0, dz), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
                BlockPos a = p.at(-4, 0, -1), b = p.at(4, 0, 7);
                BuildingTests.rim(level, Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), Math.max(a.getZ(), b.getZ()), SY);
            }
            if (def.vent() != null) {
                // a sulfur geyser: magma, the geyser, one block of water, then its open vent cell
                BlockPos v = p.at(def.vent()[0], def.vent()[1], def.vent()[2]);
                level.setBlockAndUpdate(v.below(3), Blocks.MAGMA_BLOCK.defaultBlockState());
                level.setBlockAndUpdate(v.below(1), Blocks.WATER.defaultBlockState());
                level.setBlockAndUpdate(v.below(2), Block.updateFromNeighbourShapes(Blocks.POTENT_SULFUR.defaultBlockState(), level, v.below(2)));
            }
            Buildings.Check check = Buildings.place(level, p, null, null, true);
            SHOWROOM.expect(source, def.id() + " stands in the showroom", check.ok(), check.ok() ? "at " + at.toShortString() : check.message().getString());
            if (check.ok()) built++;
        }
        level.setBlock(mam.below(), Blocks.CONCRETE.lightGray().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(mam, ModBlocks.MAM.get().defaultBlockState(), Block.UPDATE_ALL);
        SHOWROOM.expect(source, "the MAM stands in the showroom", level.getBlockState(mam).is(ModBlocks.MAM.get()), mam.toShortString());
        SHOWROOM.report(source, "setup: " + built + " of " + defs.size() + " buildings and the MAM put down");
        SHOWROOM.report(source, "result: " + SHOWROOM.totals());
        return 1;
    }

    /** A white floor (Polartt's showroom floor) over the whole showroom, its chunks kept loaded, and clear sky over it. */
    private static void floor(ServerLevel level, int x0, int z0, int x1, int z1) {
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++)
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState white = Blocks.CONCRETE.white().defaultBlockState();
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                level.setBlock(pos.set(x, SY - 1, z), white, Block.UPDATE_CLIENTS);
                for (int h = SY; h < SY + 45; h++) {
                    if (!level.getBlockState(pos.set(x, h, z)).isAir()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
    }

    private static void node(ServerLevel level, NodeType type, BlockPos at) {
        NodeLook look = NodeLook.of(type);
        level.setBlock(at.below(), look.patchStone(), Block.UPDATE_ALL);
        if (look.fluid()) level.setBlock(at, ModBlocks.NODES.get(look.id()).get().defaultBlockState().setValue(NodeBlock.PURITY, Purity.NORMAL), Block.UPDATE_ALL);
        else NodeSites.put(level, at, look, Purity.NORMAL);
    }

    // ------------------------------------------------------------------------------------------------ the camera

    /**
     * The camera on a building from the front right, 35 degrees off its front and looking down 24 degrees at the middle
     * of its lower half, far enough back that the whole of it is in the picture: the reference shots' angle.
     */
    private static int look(CommandContext<CommandSourceStack> context, String id) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        var players = source.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) return 0;
        var player = players.get(0);
        int[] box;
        if (id.equals("hub") || id.equals("wormhole_gateway")) {
            boolean gateway = id.equals("wormhole_gateway");
            BlockPos a = PrefabTests.showcase(gateway);
            Template t = gateway ? Templates.gateway() : Templates.hub();
            // the structures' frames run from minX to maxX across, the anchor on the front edge's middle
            box = new int[]{a.getX() + (t.minX + t.maxX) / 2, a.getY(), a.getZ(), t.width(), t.length(), t.height()};
        } else {
            box = PLACED.get(id);
        }
        if (box == null) {
            SHOWROOM.report(source, "look: no " + id + " in the showroom (run worldtest style setup first)");
            return 0;
        }
        double cx = box[0] + 0.5, cz = box[2] + 0.5 - box[4] / 2.0, w = box[3], d = box[4], h = box[5];
        double size = Math.max(Math.max(w, d), h * 1.15);
        double dist = Math.max(9, size * 1.45 + 4);
        double yaw = 35, pitch = 24;
        double horizontal = dist * Math.cos(Math.toRadians(pitch));
        double camX = cx + horizontal * Math.sin(Math.toRadians(yaw)), camZ = cz + horizontal * Math.cos(Math.toRadians(yaw));
        double targetY = box[1] + h * 0.4;
        double camY = targetY + dist * Math.sin(Math.toRadians(pitch)) - 1.62;
        player.getAbilities().mayfly = true;
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        // Minecraft's yaw: 180 looks north (-z); looking back toward -x as well turns it 35 degrees toward 145
        player.teleportTo(level, camX, camY, camZ, Set.of(), (float) (180 - yaw), (float) pitch, true);
        SHOWROOM.report(source, "look " + id + ": camera at " + Math.round(camX) + ", " + Math.round(camY) + ", " + Math.round(camZ));
        return 1;
    }
}
