package com.meakaandre.siftec.client.ponder;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.BuildingLogic;
import com.meakaandre.siftec.building.BuildingTemplate;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.PortBlock;
import com.meakaandre.siftec.building.PowerRole;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.power.Power;
import com.meakaandre.siftec.prefab.Template;
import com.meakaandre.siftec.prefab.Templates;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.registry.ModItems;
import com.meakaandre.siftec.vehicle.VehicleEntity;
import com.meakaandre.siftec.vehicle.VehicleKind;
import com.meakaandre.siftec.vehicle.Vehicles;
import com.zurrtum.create.client.ponder.api.PonderPalette;
import com.zurrtum.create.client.ponder.api.level.PonderLevel;
import com.zurrtum.create.client.ponder.api.registration.PonderSceneRegistrationHelper;
import com.zurrtum.create.client.ponder.api.scene.SceneBuilder;
import com.zurrtum.create.client.ponder.api.scene.SceneBuildingUtil;
import com.zurrtum.create.client.ponder.api.scene.Selection;
import com.zurrtum.create.client.ponder.foundation.PonderScene;
import com.zurrtum.create.client.ponder.foundation.instruction.WorldModifyInstruction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Ponder scenes of the guide: one for every building and vehicle the Build Gun puts down,
 * keyed to its stand-in item ({@code siftec:building_<id>}, {@link ModItems#BUILDINGS}).
 * <p>
 * A scene builds its building from the same template the Build Gun places (or, for the HUB and the Wormhole Gateway,
 * from {@link Templates}, growing level by level), so a scene can never show a building other than the real one.
 * It shows the building's own lines first (tools/guide.py, BUILDING_GUIDE, read from siftec_content.json), then the
 * lines every building shares where they apply: its ports, power, resource node, control panel, cost and unlock, and
 * how it comes down again. The lines are in the lang file: {@code siftec.ponder.<id>.text_<n>} and
 * {@code siftec.ponder.shared.<key>}.
 */
public final class BuildingScenes {
    /** The schematic every scene starts from (assets/siftec/ponder/building.nbt, one block); the scene puts the rest in. */
    static final Identifier SCHEMATIC = Siftec.id("building");
    private static final BlockState PLATE = Blocks.SMOOTH_STONE.defaultBlockState();
    private static final int FLAGS = 2;

    private BuildingScenes() {
    }

    /** Every non-dev building's scene, under its stand-in item and the Ponder tag of its Build Gun tab. */
    public static void register(PonderSceneRegistrationHelper<Identifier> helper) {
        for (BuildingDef def : Buildings.all()) {
            Item item = ModItems.BUILDINGS.get(def.id());
            if (def.dev() || item == null) continue;
            Identifier key = BuiltInRegistries.ITEM.getKey(item);
            helper.addStoryBoard(key, SCHEMATIC, (scene, util) -> program(def, scene, util), SiftecPonderPlugin.tag(def.category()));
        }
    }

    /** A building's scene; anything that goes wrong is logged and the scene says so, rather than taking the window down. */
    static void program(BuildingDef def, SceneBuilder scene, SceneBuildingUtil util) {
        scene.title(def.id(), name(def));
        try {
            if (def.vehicle()) vehicle(def, scene, util);
            else if (def.structure()) structure(def, scene, util);
            else building(def, scene, util);
        } catch (RuntimeException e) {
            Siftec.LOGGER.error("SIFTEC: the Ponder scene of {} could not be built", def.id(), e);
            throw e;
        }
        scene.markAsFinished();
    }

    // ------------------------------------------------------------------------------------------------ the text

    /** The building's own lines (tools/guide.py), in order. */
    public static List<String> lines(BuildingDef def) {
        List<String> out = new ArrayList<>();
        JsonObject raw = Milestones.raw();
        if (raw == null) return out;
        for (JsonElement e : raw.getAsJsonArray("buildings")) {
            JsonObject b = e.getAsJsonObject();
            if (!b.get("id").getAsString().equals(def.id()) || !b.has("guide")) continue;
            for (JsonElement line : b.getAsJsonArray("guide")) out.add(line.getAsString());
        }
        return out;
    }

    private static String name(BuildingDef def) {
        return def.name().getString();
    }

    /** How long a line stays up: long enough to read it. */
    private static int ticks(String text) {
        return Math.max(70, Math.min(200, 40 + text.length() * 3 / 4));
    }

    /** One of the building's own lines, pointing at a spot (or standing on its own when there is none). */
    private static void say(SceneBuilder scene, String text, @Nullable Vec3 at) {
        int t = ticks(text);
        var box = scene.overlay().showText(t).text(text).attachKeyFrame();
        if (at != null) box.pointAt(at).placeNearTarget();
        else box.independent(10);
        scene.idle(t + 10);
    }

    /** A shared line (siftec.ponder.shared.<key>), pointing at a spot. */
    private static void shared(SceneBuilder scene, String key, @Nullable Vec3 at, Object... args) {
        int t = 90;
        var box = scene.overlay().showText(t).sharedText(key, args).attachKeyFrame();
        if (at != null) box.pointAt(at).placeNearTarget();
        else box.independent(10);
        scene.idle(t + 10);
    }

    private static String cost(BuildingDef def) {
        List<String> parts = new ArrayList<>();
        for (Milestone.Cost c : def.cost()) {
            if (c.present()) parts.add(String.format(Locale.ROOT, "%,d %s", c.count(), c.label().getString()));
        }
        return parts.isEmpty() ? "nothing" : String.join(", ", parts);
    }

    private static String tab(BuildingDef def) {
        return Component.translatable(def.category().key()).getString();
    }

    /** The cost, the unlock and how it comes down: the last lines of every scene. */
    private static void closing(BuildingDef def, SceneBuilder scene, @Nullable Vec3 at) {
        shared(scene, def.vehicle() ? "vehicle_cost" : "cost", at, tab(def), cost(def));
        if (!def.unlock().isEmpty()) shared(scene, "unlock", null, Buildings.unlockName(def).getString());
        shared(scene, "dismantle", null);
    }

    // ------------------------------------------------------------------------------------------------ the view

    /** Sizes the view so the whole plate and the building's height fit the window. */
    private static void frame(SceneBuilder scene, int plate, int height) {
        scene.configureBasePlate(0, 0, plate);
        float scale = Math.min(1f, 5.5f / Math.max(plate, height * 0.9f));
        scene.scaleSceneView(scale);
        // a building taller than its plate is wide stands lower in the window
        if (height > plate) scene.setSceneOffsetY(-(height - plate) / 2f);
        scene.removeShadow();
    }

    /** The base plate, with nothing on it yet. */
    private static void plate(PonderLevel level, int size) {
        for (int x = 0; x < size; x++) for (int z = 0; z < size; z++) level.setBlock(new BlockPos(x, 0, z), PLATE, FLAGS);
    }

    // ------------------------------------------------------------------------------------------------ an ordinary building

    private static void building(BuildingDef def, SceneBuilder scene, SceneBuildingUtil util) {
        BuildingTemplate t = BuildingTemplate.of(def);
        if (t == null) throw new IllegalStateException("no template for " + def.id() + ": " + BuildingTemplate.problem(def.id()));
        int w = t.maxX - t.minX + 1, d = t.depth, h = t.height;
        int size = Math.max(w, d) + 2;
        int mx = (size - w) / 2, mz = (size - d) / 2;
        PonderLevel level = scene.getScene().getLevel();
        plate(level, size);

        // the template's blocks as the file has them (its front, the side the player stands on, towards the viewer)
        BlockPos controller = null, connector = null;
        Map<PortBlock.Kind, List<BlockPos>> ports = new EnumMap<>(PortBlock.Kind.class);
        Map<BlockPos, Direction> facing = new LinkedHashMap<>();
        for (BuildingTemplate.Cell cell : t.cells) {
            if (!cell.solid()) continue;
            BlockPos pos = at(t, cell.x(), cell.y(), cell.z(), mx, mz);
            level.setBlock(pos, cell.state(), FLAGS);
            if (cell == t.controller) controller = pos;
            if (cell.state().getBlock() instanceof PortBlock) {
                ports.computeIfAbsent(cell.state().getValue(PortBlock.KIND), k -> new ArrayList<>()).add(pos);
                facing.put(pos, cell.state().getValue(PortBlock.FACING));
            }
            Identifier id = BuiltInRegistries.BLOCK.getKey(cell.state().getBlock());
            if (connector == null && id.getNamespace().equals("create_new_age") && id.getPath().contains("connector")) connector = pos;
        }
        // what it stands on or over: a node (ore for a miner, the fluid node for an extractor), water, a geyser vent
        BlockPos node = null;
        if (def.node() && def.nodeCell() != null) {
            int[] c = def.nodeCell();
            node = at(t, c[0], c[1], c[2], mx, mz);
            BlockState look = def.fluidNode().isEmpty() ? Blocks.IRON_ORE.defaultBlockState()
                : ModBlocks.NODES.containsKey(def.fluidNode()) ? ModBlocks.NODES.get(def.fluidNode()).get().defaultBlockState() : Blocks.IRON_ORE.defaultBlockState();
            level.setBlock(node, look, FLAGS);
        }
        if (def.intake() != null) {
            int[] c = def.intake();
            level.setBlock(at(t, c[0], c[1], c[2], mx, mz), Blocks.WATER.defaultBlockState(), FLAGS);
        }
        if (def.vent() != null) {
            int[] c = def.vent();
            level.setBlock(at(t, c[0], c[1], c[2], mx, mz).atY(0), Blocks.POTENT_SULFUR.defaultBlockState(), FLAGS);
        }
        // what the scene starts from on every replay
        level.createBackup();

        frame(scene, size, h + 1);
        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(util.select().fromTo(0, 1, 0, size - 1, h + 1, size - 1), Direction.DOWN);
        scene.idle(25);

        Vec3 top = util.vector().of(size / 2.0, h + 1, size / 2.0);
        List<String> lines = lines(def);
        for (int i = 0; i < lines.size(); i++) say(scene, lines.get(i), i == 0 ? top : null);

        if (node != null) {
            scene.overlay().showOutline(PonderPalette.GREEN, "node", util.select().position(node), 90);
            shared(scene, "node", util.vector().centerOf(node));
        }
        for (PortBlock.Kind kind : PortBlock.Kind.values()) {
            List<BlockPos> list = ports.get(kind);
            if (list == null || list.isEmpty()) continue;
            Selection sel = util.select().position(list.getFirst());
            for (BlockPos p : list) sel = sel.add(util.select().position(p));
            scene.overlay().showOutline(kind.input() ? PonderPalette.INPUT : PonderPalette.OUTPUT, kind, sel, 90);
            BlockPos first = list.getFirst();
            shared(scene, kind.getSerializedName(), util.vector().blockSurface(first, facing.getOrDefault(first, Direction.SOUTH)));
        }
        power(def, scene, util, controller, connector);
        if (controller != null) {
            Vec3 panel = util.vector().centerOf(controller);
            scene.overlay().showOutline(PonderPalette.WHITE, "panel", util.select().position(controller), 90);
            shared(scene, "panel", panel);
            if (BuildingLogic.hasFilter(def)) shared(scene, "panel_filter", panel);
        }
        closing(def, scene, top);
    }

    /** A cell in the Build Gun frame (x from the middle column, z from the front row) as a spot in the scene. */
    private static BlockPos at(BuildingTemplate t, int x, int y, int z, int mx, int mz) {
        return new BlockPos(x - t.minX + mx, y + 1, t.depth - 1 - z + mz);
    }

    /** What it does in the power grid: draws, gives or stores power, and where its wires go. */
    private static void power(BuildingDef def, SceneBuilder scene, SceneBuildingUtil util, @Nullable BlockPos controller, @Nullable BlockPos connector) {
        Vec3 at = controller == null ? null : util.vector().centerOf(controller);
        PowerRole kind = def.power();
        boolean generator = def.mw() > 0 && kind.givesPower();
        if (generator) {
            shared(scene, "power_make", at, fmt(def.mw()), Power.fmt(Power.su(def.mw())));
        } else if (def.su() > 0) {
            shared(scene, "power_use", at, Power.fmt(def.su()));
        }
        if (connector != null) {
            scene.overlay().showOutline(PonderPalette.BLUE, "connector", util.select().position(connector), 90);
            shared(scene, "power_wire", util.vector().topOf(connector));
        }
        if (generator || kind == PowerRole.STORAGE) shared(scene, "fuse", at);
    }

    private static String fmt(double n) {
        return n == Math.rint(n) ? String.format(Locale.ROOT, "%,d", (long) n) : String.format(Locale.ROOT, "%,.1f", n);
    }

    // ------------------------------------------------------------------------------------------------ the HUB and the Wormhole Gateway

    /**
     * The HUB or the Wormhole Gateway, growing level by level as they do in the world: the HUB through its upgrades,
     * the Gateway through its phases. Their front (the terminal's side) faces the viewer.
     */
    private static void structure(BuildingDef def, SceneBuilder scene, SceneBuildingUtil util) {
        boolean hub = def.hub();
        Template t = hub ? Templates.hub() : Templates.gateway();
        int w = t.width(), d = t.length(), h = t.height();
        int size = Math.max(w, d) + 2;
        int mx = (size - w) / 2, mz = (size - d) / 2;
        PonderLevel level = scene.getScene().getLevel();
        plate(level, size);
        Map<Long, BlockPos> spots = new LinkedHashMap<>();
        for (long key : t.keys()) {
            BlockPos p = BlockPos.of(key);
            // turned half way round: the terminal's side, the structure's low z, comes to the front
            spots.put(key, new BlockPos(t.maxX - p.getX() + mx, p.getY() - t.minY + 1, t.maxZ - p.getZ() + mz));
        }
        for (long key : t.keys()) {
            BlockState state = t.at(key, 0);
            if (state != null && !state.isAir()) level.setBlock(spots.get(key), state.rotate(Rotation.CLOCKWISE_180), FLAGS);
        }
        level.createBackup();

        frame(scene, size, h + 1);
        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(util.select().fromTo(0, 1, 0, size - 1, h + 1, size - 1), Direction.DOWN);
        scene.idle(25);

        int[] terminal = hub ? Templates.HUB_TERMINAL : Templates.GATEWAY_TERMINAL;
        BlockPos term = spots.getOrDefault(Template.key(terminal[0], terminal[1], terminal[2]),
            new BlockPos(size / 2, 1, size / 2));
        Vec3 top = util.vector().of(size / 2.0, h + 1, size / 2.0);
        List<String> lines = lines(def);
        int line = 0;
        if (line < lines.size()) say(scene, lines.get(line++), util.vector().topOf(term));
        if (line < lines.size()) {
            scene.overlay().showOutline(PonderPalette.WHITE, "terminal", util.select().position(term), 120);
            say(scene, lines.get(line++), util.vector().topOf(term));
        }
        // the levels, one after another: the HUB's upgrades, the Gateway's phases
        String growing = line < lines.size() ? lines.get(line++) : null;
        int last = t.maxLevel;
        for (int lvl = 1; lvl <= last; lvl++) {
            scene.addInstruction(new Grow(t, spots, lvl, util.select().fromTo(0, 1, 0, size - 1, h + 1, size - 1)));
            if (lvl == 1 && growing != null) {
                int dur = ticks(growing);
                scene.overlay().showText(dur).text(growing).attachKeyFrame().independent(10);
            }
            scene.idle(hub ? 30 : 45);
        }
        if (growing != null) scene.idle(30);
        while (line < lines.size()) say(scene, lines.get(line++), line == lines.size() ? top : null);
        closing(def, scene, top);
    }

    /** Sets the blocks that change from one level of a structure to the next, and redraws what shows them. */
    private static final class Grow extends WorldModifyInstruction {
        private final Map<BlockPos, BlockState> changes = new LinkedHashMap<>();

        Grow(Template t, Map<Long, BlockPos> spots, int level, Selection all) {
            super(all);
            for (long key : t.keys()) {
                BlockState before = t.at(key, level - 1), after = t.at(key, level);
                BlockState was = before == null || before.isAir() ? null : before;
                BlockState now = after == null || after.isAir() ? null : after;
                if (was == now) continue;
                changes.put(spots.get(key), now == null ? Blocks.AIR.defaultBlockState() : now.rotate(Rotation.CLOCKWISE_180));
            }
        }

        @Override
        protected void runModification(Selection selection, PonderScene scene) {
            PonderLevel level = scene.getLevel();
            for (Map.Entry<BlockPos, BlockState> e : changes.entrySet()) level.setBlock(e.getKey(), e.getValue(), FLAGS);
        }

        @Override
        protected boolean needsRedraw() {
            return true;
        }
    }

    // ------------------------------------------------------------------------------------------------ a vehicle

    private static void vehicle(BuildingDef def, SceneBuilder scene, SceneBuildingUtil util) {
        VehicleKind kind = Vehicles.kind(def);
        if (kind == null) throw new IllegalStateException("no vehicle kind for " + def.id());
        int size = Math.max(kind.width, kind.length) + 4;
        PonderLevel level = scene.getScene().getLevel();
        plate(level, size);
        level.createBackup();

        frame(scene, size, kind.height + 1);
        scene.showBasePlate();
        scene.idle(10);
        double cx = size / 2.0, cz = size / 2.0;
        scene.world().createEntity(world -> {
            VehicleEntity e = new VehicleEntity(Vehicles.type(kind), world);
            e.setPos(cx, 1, cz);
            // a three-quarter view: its front turned towards the viewer
            e.setYRot(-30);
            e.yRotO = -30;
            return e;
        });
        scene.idle(20);
        Vec3 top = util.vector().of(cx, kind.height + 1.2, cz);
        for (String line : lines(def)) say(scene, line, top);
        closing(def, scene, top);
    }
}
