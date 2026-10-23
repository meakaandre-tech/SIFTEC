package com.meakaandre.siftec.building;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.blueprint.DesignerBlockEntity;
import com.meakaandre.siftec.claim.Claims;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.geyser.Vents;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.power.PowerGrids;
import com.meakaandre.siftec.sim.Factories;
import com.meakaandre.siftec.sink.Shop;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The building framework: the list of buildings, and placing, dismantling and protecting them.
 * <p>
 * A building goes down whole: its template's blocks are set in one go, every block is listed in the
 * {@link BuildingIndex} under the controller, and the build cost is taken from the inventory and kept in the
 * controller as the refund. No block of it can be mined, wrenched, blown up, pushed, drilled, deployed on (but by
 * its own Deployers), broken by a Wither or cleared by a Blueprint Designer; the Build Gun's dismantle mode takes it all
 * away again and gives back the refund and anything inside, its tanks' fluid packaged. If a block goes anyway, it drops
 * nothing for itself (mixin/claim/BuildingDropsMixin), the controller notices and the whole building comes down,
 * dropping the refund and contents.
 */
public final class Buildings {
    /** How far the Build Gun reaches, as Satisfactory's reaches further than a hand. */
    public static final int RANGE = 32;
    /** Taken off a box's max corner so a box ending exactly on a block edge does not reach into the next block. */
    static final double EDGE_EPSILON = 1e-4;
    static final int SET = Block.UPDATE_ALL;
    /** Sent to clients, but no neighbour is told (nothing next to the block reacts to it going). */
    static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static final Map<String, BuildingDef> DEFS = new LinkedHashMap<>();

    private Buildings() {
    }

    // ------------------------------------------------------------------------------------------------ the list

    /** Reads the buildings from siftec_content.json (tools/content.py, BUILDINGS). */
    public static void load() {
        DEFS.clear();
        JsonObject raw = Milestones.raw();
        if (raw == null || !raw.has("buildings")) return;
        for (JsonElement e : raw.getAsJsonArray("buildings")) {
            JsonObject o = e.getAsJsonObject();
            List<Milestone.Cost> cost = new ArrayList<>();
            for (JsonElement c : o.getAsJsonArray("cost")) {
                cost.add(new Milestone.Cost(c.getAsJsonObject().get("item").getAsString(), c.getAsJsonObject().get("count").getAsInt()));
            }
            boolean node = o.has("node") && o.get("node").getAsBoolean();
            int[] nodeCell = null;
            if (node) {
                nodeCell = new int[]{0, 0, 1};
                if (o.has("node_cell")) {
                    var a = o.getAsJsonArray("node_cell");
                    nodeCell = new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
                }
            }
            BuildingDef def = new BuildingDef(o.get("id").getAsString(), Category.of(o.get("category").getAsString()), List.copyOf(cost),
                o.has("unlock") ? o.get("unlock").getAsString() : "", o.has("dev") && o.get("dev").getAsBoolean(), node, nodeCell,
                Logic.of(o.has("logic") ? o.get("logic").getAsString() : ""), o.has("su") ? o.get("su").getAsInt() : 0, o.has("break") ? o.get("break").getAsInt() : 0, o.has("fortune") ? o.get("fortune").getAsInt() : 0,
                PowerRole.of(o.has("power") ? o.get("power").getAsString() : ""), o.has("mw") ? o.get("mw").getAsDouble() : 0, o.has("water") ? o.get("water").getAsDouble() : 0,
                o.has("lines") ? o.get("lines").getAsInt() : 0, cell(o, "vent"), o.has("prefab") ? o.get("prefab").getAsString() : "",
                o.has("fluid") ? o.get("fluid").getAsString() : "", o.has("rate") ? o.get("rate").getAsInt() : 0,
                o.has("fluid_node") ? o.get("fluid_node").getAsString() : "", cell(o, "intake"));
            DEFS.put(def.id(), def);
        }
    }

    private static int @Nullable [] cell(JsonObject o, String key) {
        if (!o.has(key)) return null;
        var a = o.getAsJsonArray(key);
        return new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
    }

    public static @Nullable BuildingDef def(String id) {
        return DEFS.get(id);
    }

    public static List<BuildingDef> all() {
        return List.copyOf(DEFS.values());
    }

    /** The buildings of a tab, in list order; dev buildings only for creative players. */
    public static List<BuildingDef> of(Category category, boolean creative) {
        List<BuildingDef> out = new ArrayList<>();
        for (BuildingDef def : DEFS.values()) if (def.category() == category && (creative || !def.dev())) out.add(def);
        return out;
    }

    // ------------------------------------------------------------------------------------------------ checking

    /**
     * True if the company has not unlocked the building yet: its milestone, Wormhole phase or MAM research is not among
     * what the company has done. Unlocks belong to the company, so every member builds what one of them unlocked.
     */
    public static boolean locked(BuildingDef def, Company company) {
        if (def.unlock().startsWith("shop:")) return !Shop.owns(company, "unlock:" + def.unlock().substring(5));
        return !def.unlock().isEmpty() && !company.has(def.unlock());
    }

    /** The name of what unlocks the building (a milestone or MAM node), or its id if no such milestone is known. */
    public static Component unlockName(BuildingDef def) {
        if (def.unlock().startsWith("shop:")) return Component.translatable("siftec.shop.entry." + def.unlock().substring(5));
        Milestone m = Milestones.get(def.unlock());
        return m == null ? Component.literal(def.unlock()) : m.name();
    }

    /** What a placement check found. */
    public static final class Check {
        public final Placement placement;
        public final List<BlockPos> blocked = new ArrayList<>();
        /** Costs not covered by the inventory, with how many are missing. */
        public final List<Milestone.Cost> missing = new ArrayList<>();
        public @Nullable String reason;
        public Object[] args = new Object[0];

        public Check(Placement placement) {
            this.placement = placement;
        }

        public boolean ok() {
            return reason == null && blocked.isEmpty() && missing.isEmpty();
        }

        public Component message() {
            if (reason != null) return Component.translatable(reason, args);
            if (!blocked.isEmpty()) return Component.translatable("siftec.build_gun.blocked", blocked.size());
            if (!missing.isEmpty()) {
                Milestone.Cost first = missing.getFirst();
                return Component.translatable("siftec.build_gun.missing", first.count(), first.icon().getHoverName());
            }
            return Component.translatable("siftec.build_gun.fits", placement.def().name());
        }

        public void fail(String reason, Object... args) {
            if (this.reason != null) return;
            this.reason = reason;
            this.args = args;
        }
    }

    /**
     * Checks a placement: the template is readable, every cell (inside space too) is free (air, plants, snow,
     * water), nobody stands where a block would go, and with a company: the building is unlocked and the land is not
     * another company's. With a payer the cost is checked as well. Creative players skip unlocks, land and cost.
     */
    public static Check check(Level level, Placement p, @Nullable Company company, @Nullable Container payer, boolean creative) {
        Check check = new Check(p);
        BuildingTemplate t = BuildingTemplate.of(p.def());
        if (t == null) {
            check.fail("siftec.build_gun.no_template", p.def().id());
            return check;
        }
        if (level.isOutsideBuildHeight(p.anchor().getY()) || level.isOutsideBuildHeight(p.anchor().getY() + t.height - 1)) {
            check.fail("siftec.prefab.height");
            return check;
        }
        Set<Long> solid = new HashSet<>();
        for (BuildingTemplate.Cell cell : t.cells) {
            BlockPos pos = p.at(cell);
            if (!level.isLoaded(pos)) {
                check.fail("siftec.prefab.unloaded");
                return check;
            }
            if (!level.getBlockState(pos).canBeReplaced()) check.blocked.add(pos);
            if (cell.solid()) solid.add(pos.asLong());
        }
        // an ore node's position is never building space, grown or empty; a node building's own node cell is its node
        BlockPos ownNode = BuildingNodes.ownNodeCell(p);
        AABB nodeBox = p.bounds(t);
        for (BlockPos pos : BlockPos.betweenClosed((int) nodeBox.minX, (int) nodeBox.minY, (int) nodeBox.minZ, (int) nodeBox.maxX - 1, (int) nodeBox.maxY - 1, (int) nodeBox.maxZ - 1)) {
            if (pos.equals(ownNode) || !BuildingNodes.isNode(level, pos)) continue;
            if (!check.blocked.contains(pos)) check.blocked.add(pos.immutable());
        }
        if (p.def().node()) BuildingNodes.nodeUnder(level, p, t, check);
        int[] intake = p.def().intake();
        if (intake != null) {
            BlockPos water = p.at(intake[0], intake[1], intake[2]);
            var fluid = level.getFluidState(water);
            if (!fluid.isSource() || !fluid.is(FluidTags.WATER)) check.fail("siftec.build_gun.no_water");
        }
        int[] vent = p.def().vent();
        if (vent != null && Vents.findVent(level, p.at(vent[0], vent[1], vent[2])) == null) {
            check.fail("siftec.power.no_geyser_place");
        }
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, p.bounds(t))) {
            AABB box = entity.getBoundingBox();
            for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX - EDGE_EPSILON, box.maxY - EDGE_EPSILON, box.maxZ - EDGE_EPSILON))) {
                if (solid.contains(pos.asLong())) {
                    check.fail("siftec.prefab.entity");
                    break;
                }
            }
        }
        if (creative) return check;
        if (p.def().dev()) check.fail("siftec.build_gun.dev");
        if (company != null && locked(p.def(), company)) check.fail("siftec.build_gun.locked", unlockName(p.def()));
        if (company != null && level instanceof ServerLevel server) land(server, p, t, company, check);
        if (payer != null) {
            for (Milestone.Cost cost : p.def().cost()) {
                if (!cost.present()) continue;
                int have = 0;
                for (int i = 0; i < payer.getContainerSize(); i++) {
                    ItemStack stack = payer.getItem(i);
                    if (cost.matches(stack)) have += stack.getCount();
                }
                if (have < cost.count()) check.missing.add(new Milestone.Cost(cost.key(), cost.count() - have));
            }
        }
        return check;
    }

    private static void land(ServerLevel level, Placement p, BuildingTemplate t, Company company, Check check) {
        Claims.Claim claim = Claims.foreign(level, p.bounds(t), company.id);
        if (claim != null) check.fail("siftec.prefab.claimed", Claims.holder(level, claim));
    }

    // ------------------------------------------------------------------------------------------------ placing

    /**
     * Checks and, if everything is in order, puts the building down: takes the cost from the payer (none in
     * creative), lists every block under the controller and sets them all. The check is returned either way.
     */
    public static Check place(ServerLevel level, Placement p, @Nullable Company company, @Nullable Container payer, boolean creative) {
        Check check = check(level, p, company, payer, creative);
        if (!check.ok()) return check;
        return placeChecked(level, p, company, creative ? null : payer, check);
    }

    /** Puts down a building whose check has passed, paid from the payer (null: free, as in creative). */
    static Check placeChecked(ServerLevel level, Placement p, @Nullable Company company, @Nullable Container payer, Check check) {
        BuildingTemplate t = BuildingTemplate.of(p.def());
        List<ItemStack> refund = payer == null ? List.of() : take(payer, p.def().cost());
        BlockPos controllerPos = p.at(t.controller);
        BuildingIndex index = BuildingIndex.get(level);
        // listed first, so the protections already hold while the blocks go in
        for (BuildingTemplate.Cell cell : t.members) index.put(level, p.at(cell), controllerPos);
        for (BuildingTemplate.Cell cell : t.cells) {
            if (cell.solid()) continue;
            BlockPos pos = p.at(cell);
            if (!level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), SET);
        }
        for (BuildingTemplate.Cell cell : t.members) level.setBlock(p.at(cell), p.orient(cell.state()), SET);
        wire(level, p, t, controllerPos, company == null ? "" : company.id);
        if (level.getBlockEntity(controllerPos) instanceof ControllerBlockEntity controller) {
            controller.shape = BuildingRepair.hash(t);
            controller.buildingId = p.def().id();
            controller.anchor = p.anchor();
            controller.facing = p.facing();
            controller.flip = p.flip();
            controller.companyId = company == null ? "" : company.id;
            controller.refund.clear();
            controller.refund.addAll(refund);
            controller.node = p.def().node() ? BuildingNodes.nodeIn(level, p, t) : null;
            controller.setChanged();
            BuildingLogic.placed(level, controller);
        }
        PowerGrids.dirty();
        Siftec.LOGGER.info("SIFTEC: building {} placed at {} facing {}{} ({} blocks, {} refund stacks)", p.def().id(), controllerPos.toShortString(),
            p.facing(), p.flip() ? " flipped" : "", t.members.size(), refund.size());
        return check;
    }

    /**
     * Joins up a building's blocks once they are set: each port is told the cell it feeds or drains and its controller,
     * and the machines inside are the company's, so they run the recipes it has unlocked.
     */
    static void wire(ServerLevel level, Placement p, BuildingTemplate t, BlockPos controllerPos, String companyId) {
        for (BuildingTemplate.Cell cell : t.members) {
            BlockPos pos = p.at(cell);
            if (level.getBlockEntity(pos) instanceof PortBlockEntity port) {
                Direction out = p.orient(cell.state().getValue(PortBlock.FACING));
                port.target = cell.target() != null ? p.at(cell.target()[0], cell.target()[1], cell.target()[2]) : pos.relative(out.getOpposite());
                port.controller = controllerPos;
                port.setChanged();
            }
        }
        if (companyId.isEmpty()) return;
        for (BuildingTemplate.Cell cell : t.members) {
            BlockEntity be = level.getBlockEntity(p.at(cell));
            if (be != null) be.setAttached(Ownership.OWNER, companyId);
        }
    }

    /**
     * Takes the costs out of a container (the check has made sure they are there) and returns what was taken. Taken
     * with removeItem, not by shrinking the stack, so a backpack slot (see {@link com.meakaandre.siftec.backpack.Carried})
     * saves what is left in it.
     */
    public static List<ItemStack> take(Container payer, List<Milestone.Cost> costs) {
        List<ItemStack> taken = new ArrayList<>();
        for (Milestone.Cost cost : costs) {
            if (!cost.present()) continue;
            int left = cost.count();
            for (int i = 0; i < payer.getContainerSize() && left > 0; i++) {
                ItemStack stack = payer.getItem(i);
                if (!cost.matches(stack)) continue;
                ItemStack got = payer.removeItem(i, Math.min(left, stack.getCount()));
                left -= got.getCount();
                taken.add(got);
            }
        }
        payer.setChanged();
        return taken;
    }

    // ------------------------------------------------------------------------------------------------ taking down

    /**
     * Takes the building down on purpose (the Build Gun's dismantle mode) and returns the refund and everything
     * that was inside it.
     */
    public static List<ItemStack> dismantle(ServerLevel level, ControllerBlockEntity controller) {
        controller.removing = true;
        Placement p = controller.placement();
        List<ItemStack> out = new ArrayList<>(BuildingTeardown.copy(controller.refund));
        controller.refund.clear();
        out.addAll(controller.takeContents());
        BlockPos controllerPos = controller.getBlockPos().immutable();
        Factories.removed(level, controllerPos);
        if (p != null) out.addAll(BuildingTeardown.takeDown(level, p, controllerPos));
        // the Converter's cell is part of its machine, not something it holds: plain, loaded or charged, it stays
        out.removeIf(Processors::cellBearing);
        if (level.getBlockState(controllerPos).getBlock() instanceof ControllerBlock) {
            level.setBlock(controllerPos, Blocks.AIR.defaultBlockState(), controller instanceof DesignerBlockEntity ? QUIET : SET);
        }
        Siftec.LOGGER.info("SIFTEC: building {} at {} dismantled; {} stacks given back", controller.buildingId, controllerPos.toShortString(), out.size());
        PowerGrids.dirty();
        return out;
    }

    /**
     * Paints the building (the Customizer): every panel the paint touches is set to its painted block, keeping the way
     * it faced where both blocks have it. Nothing else of the building changes.
     */
    public static void repaint(ServerLevel level, ControllerBlockEntity controller, Paint paint) {
        Placement p = controller.placement();
        if (p == null) return;
        BuildingTemplate t = BuildingTemplate.of(p.def());
        if (t == null) return;
        boolean storage = p.def().logic() == Logic.STORAGE;
        controller.paint = paint.write();
        controller.setChanged();
        for (BuildingTemplate.Cell cell : t.members) {
            BlockPos pos = p.at(cell);
            if (!level.isLoaded(pos)) continue;
            BlockState want = paint.look(cell, storage);
            if (level.getBlockState(pos).getBlock() != want.getBlock()) level.setBlock(pos, p.orient(want), SET);
        }
    }

    /** Lists the building's blocks again (a controller does it once after loading, in case the index lost them). */
    static void reindex(ServerLevel level, ControllerBlockEntity controller) {
        Placement p = controller.placement();
        if (p == null) return;
        BuildingTemplate t = BuildingTemplate.of(p.def());
        if (t == null) return;
        BuildingIndex index = BuildingIndex.get(level);
        BlockPos at = controller.getBlockPos();
        for (BuildingTemplate.Cell cell : t.members) index.put(level, p.at(cell), at);
    }

    // ------------------------------------------------------------------------------------------------ events

    public static void register() {
        load();
        ServerLifecycleEvents.SERVER_STARTED.register(BuildingIndex::serverStarted);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            BuildingIndex.serverStopped();
            BuildingTeardown.GONE.clear();
            CHANGED.clear();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> BuildingTeardown.comeDown());
        BuildingProtection.register();
    }

    /** Controllers one of whose blocks changed since they last looked (for the foundations, which do not look every second). */
    private static final Map<String, LongOpenHashSet> CHANGED = new HashMap<>();

    /** A block changed in the world (LevelChunkMixin): if it is a building's, its controller looks over its blocks again. */
    public static void blockChanged(ServerLevel level, BlockPos pos) {
        BlockPos controller = BuildingIndex.controllerOf(level, pos);
        if (controller == null) return;
        CHANGED.computeIfAbsent(level.dimension().identifier().toString(), k -> new LongOpenHashSet()).add(controller.asLong());
    }

    /** True (once) if one of the controller's blocks changed since it last asked. */
    static boolean takeChanged(ServerLevel level, BlockPos controller) {
        LongOpenHashSet set = CHANGED.get(level.dimension().identifier().toString());
        return set != null && set.remove(controller.asLong());
    }
}
