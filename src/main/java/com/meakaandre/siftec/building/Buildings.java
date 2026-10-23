package com.meakaandre.siftec.building;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.blueprint.DesignerBlockEntity;
import com.meakaandre.siftec.claim.Claims;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.death.DeathCrates;
import com.meakaandre.siftec.equip.Ziplines;
import com.meakaandre.siftec.geyser.Vents;
import com.meakaandre.siftec.hub.HubBlock;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.node.NodePlacer;
import com.meakaandre.siftec.node.NodeSites;
import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.power.PowerGrids;
import com.meakaandre.siftec.sim.Factories;
import com.meakaandre.siftec.sink.Shop;
import com.meakaandre.siftec.util.Chunks;
import com.meakaandre.siftec.util.Drops;
import com.meakaandre.siftec.util.Transfers;
import com.zurrtum.create.AllItems;
import com.zurrtum.create.content.processing.basin.BasinBlockEntity;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.antarcticgardens.cna.content.electricity.connector.AbstractElectricalConnector;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
    private static final int SET = Block.UPDATE_ALL;
    /** Sent to clients, but no neighbour is told (nothing next to the block reacts to it going). */
    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

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
        BlockPos ownNode = ownNodeCell(p);
        AABB nodeBox = p.bounds(t);
        for (BlockPos pos : BlockPos.betweenClosed((int) nodeBox.minX, (int) nodeBox.minY, (int) nodeBox.minZ, (int) nodeBox.maxX - 1, (int) nodeBox.maxY - 1, (int) nodeBox.maxZ - 1)) {
            if (pos.equals(ownNode) || !isNode(level, pos)) continue;
            if (!check.blocked.contains(pos)) check.blocked.add(pos.immutable());
        }
        if (p.def().node()) nodeUnder(level, p, t, check);
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
            for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX - 1e-4, box.maxY - 1e-4, box.maxZ - 1e-4))) {
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

    /**
     * A node building goes over a resource node, grown or mined (the node block is in its box, in a cell its template
     * leaves out), and only once the node's clump is mined away. Applies in creative too: the clump is part of the world.
     */
    private static void nodeUnder(Level level, Placement p, BuildingTemplate t, Check check) {
        BlockPos node = nodeIn(level, p, t);
        if (node == null) {
            check.fail("siftec.build_gun.no_node", p.def().name());
            return;
        }
        int left = NodePlacer.clumpLeft(level, node);
        if (left > 0) check.fail("siftec.build_gun.clump", left);
    }

    /** An ore node's position, its ore grown or the spot empty and growing back (nodes are kept by position: node.NodeSites). */
    static boolean isNode(Level level, BlockPos pos) {
        return NodeSites.isSite(level, pos);
    }

    /** The node a building stands on: an ore node for a miner, or a fluid node of its type for an extractor (oil, nitrogen). */
    static boolean isNode(BuildingDef def, Level level, BlockPos pos) {
        if (def.fluidNode().isEmpty()) return isNode(level, pos);
        return level.getBlockState(pos).getBlock() instanceof NodeBlock node && node.look.fluid() && node.look.type().id().equals(def.fluidNode());
    }

    /**
     * The node a node building stands over: the node in its own node cell (grown or mined), or null. A node anywhere
     * else in its box is not its node (and blocks it: {@link #check}).
     */
    public static @Nullable BlockPos nodeIn(Level level, Placement p, BuildingTemplate t) {
        BlockPos cell = ownNodeCell(p);
        return cell != null && isNode(p.def(), level, cell) ? cell : null;
    }

    /** Where a node building's node goes (its template's node cell), or null for any other building. */
    public static @Nullable BlockPos ownNodeCell(Placement p) {
        int[] c = p.def().nodeCell();
        return p.def().node() && c != null ? p.at(c[0], c[1], c[2]).immutable() : null;
    }

    /**
     * A node building is moved so its node cell lands on the nearest node within three blocks of where it was aimed
     * (the same height or one above or below), so the player need not line it up by hand. Anything else is left as it is.
     */
    public static Placement snapToNode(Level level, Placement p) {
        int[] c = p.def().nodeCell();
        if (!p.def().node() || c == null) return p;
        BlockPos aim = p.at(c[0], c[1], c[2]);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(aim.offset(-3, -1, -3), aim.offset(3, 1, 3))) {
            if (!level.isLoaded(pos) || !isNode(p.def(), level, pos)) continue;
            double d = pos.distSqr(aim);
            if (d < bestDist) {
                bestDist = d;
                best = pos.immutable();
            }
        }
        return best == null ? p : p.moved(best.getX() - aim.getX(), best.getY() - aim.getY(), best.getZ() - aim.getZ());
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
            controller.node = p.def().node() ? nodeIn(level, p, t) : null;
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
        List<ItemStack> out = new ArrayList<>(copy(controller.refund));
        controller.refund.clear();
        out.addAll(controller.takeContents());
        BlockPos controllerPos = controller.getBlockPos().immutable();
        Factories.removed(level, controllerPos);
        if (p != null) out.addAll(takeDown(level, p, controllerPos));
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
     * Unlists and removes every block of a placed building but the controller, emptying containers first, and
     * returns what they held. Blocks that are no longer the building's own (something else stands there now) are
     * left alone.
     */
    private static List<ItemStack> takeDown(ServerLevel level, Placement p, BlockPos controllerPos) {
        return takeDown(level, p, controllerPos, Set.of());
    }

    /** As {@link #takeDown(ServerLevel, Placement, BlockPos)}, leaving alone the positions in {@code keep} (a new building's). */
    private static List<ItemStack> takeDown(ServerLevel level, Placement p, BlockPos controllerPos, Set<Long> keep) {
        List<ItemStack> out = new ArrayList<>();
        BuildingTemplate t = BuildingTemplate.of(p.def());
        if (t == null) return out;
        BuildingIndex index = BuildingIndex.get(level);
        List<BlockPos> remove = new ArrayList<>();
        Paint paint = level.getBlockEntity(controllerPos) instanceof ControllerBlockEntity c ? Paint.parse(c.paint) : Paint.NONE;
        boolean storage = p.def().logic() == Logic.STORAGE;
        for (BuildingTemplate.Cell cell : t.members) {
            BlockPos pos = p.at(cell);
            if (keep.contains(pos.asLong())) continue;
            index.remove(level, pos, controllerPos);
            if (pos.equals(controllerPos)) continue;
            BlockState now = level.getBlockState(pos);
            if (!paint.fits(cell, now.getBlock(), storage)) continue;
            out.addAll(contents(level, pos));
            remove.add(pos);
        }
        // machines that drop what they hold as they go (a basin's input, say) are caught too: it all comes back. Things
        // lying in a Blueprint Designer's volume are not the building's, and stay where they are
        AABB box = p.bounds(t).inflate(1);
        AABB open = DesignerBlockEntity.openSpace(p);
        Set<UUID> before = new HashSet<>();
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) {
            if (open != null && open.contains(e.position())) {
                before.add(e.getUUID());
                continue;
            }
            // the Particle Accelerator's Cores in flight never despawn and cannot be picked up: they come back with the
            // building (a Converter cell lying loose is part of its machine and goes with it)
            if (Processors.isCore(e.getItem())) {
                out.add(e.getItem().copy());
                e.discard();
            } else if (Processors.cellBearing(e.getItem())) {
                e.discard();
            } else {
                before.add(e.getUUID());
            }
        }
        // a Blueprint Designer's floor and frame go without telling their neighbours, so nothing built in its volume (a
        // torch or carpet on the floor, a lever on a pillar) pops off as they go
        int flags = open != null ? QUIET : SET;
        for (BlockPos pos : remove) level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) {
            if (before.contains(e.getUUID()) || e.getItem().isEmpty()) continue;
            out.add(e.getItem().copy());
            e.discard();
        }
        return out;
    }

    /**
     * Everything a block holds: a vanilla container's slots, or what its item storage lets out, and what its tanks hold
     * ({@link #fluids}). Ports and the controller hold nothing.
     */
    public static List<ItemStack> contents(ServerLevel level, BlockPos pos) {
        List<ItemStack> out = new ArrayList<>();
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || be instanceof PortBlockEntity || be instanceof ControllerBlockEntity) return out;
        out.addAll(fluids(level, pos));
        // a Basin keeps its input and output in one inventory that its item storage only lets the output out of
        Container container = be instanceof Container c ? c
            : be instanceof BasinBlockEntity basin ? basin.itemCapability : null;
        if (container != null) {
            Transfers.takeAll(container, container.getContainerSize(), out);
            container.setChanged();
            return out;
        }
        Storage<ItemVariant> storage = ItemStorage.SIDED.find(level, pos, null);
        if (storage == null) return out;
        try (Transaction tx = Transaction.openOuter()) {
            for (StorageView<ItemVariant> view : storage.nonEmptyViews()) {
                ItemVariant variant = view.getResource();
                long n = view.extract(variant, view.getAmount(), tx);
                while (n > 0) {
                    int part = (int) Math.min(n, variant.toStack().getMaxStackSize());
                    out.add(variant.toStack(part));
                    n -= part;
                }
            }
            tx.commit();
        }
        return out;
    }

    /**
     * Empties the tanks of a block being dismantled into items: each fluid the Packager packages comes back as its
     * packaged fluid (one per 1 m3, as Satisfactory gives it back), any other fluid with a bucket as full buckets. Less
     * than one package or bucket is too little to give back and goes with the tank; a fluid with neither is left in it.
     */
    public static List<ItemStack> fluids(ServerLevel level, BlockPos pos) {
        List<ItemStack> out = new ArrayList<>();
        Storage<FluidVariant> tanks = FluidStorage.SIDED.find(level, pos, null);
        if (tanks == null) return out;
        try (Transaction tx = Transaction.openOuter()) {
            for (StorageView<FluidVariant> view : tanks.nonEmptyViews()) {
                var variant = view.getResource();
                if (variant.isBlank()) continue;
                ItemStack unit = ProductionLogic.packaged(variant.getFluid());
                long size;
                if (!unit.isEmpty()) {
                    size = (long) ProductionLogic.packagedMb(variant.getFluid()) * (FluidConstants.BUCKET / 1000);
                } else if (variant.getFluid().getBucket() != Items.AIR) {
                    unit = new ItemStack(variant.getFluid().getBucket());
                    size = FluidConstants.BUCKET;
                } else {
                    continue;
                }
                if (size <= 0) continue;
                long units = view.getAmount() / size;
                if (units <= 0) continue;
                long took = view.extract(variant, view.getAmount(), tx);
                units = took / size;
                while (units > 0) {
                    int part = (int) Math.min(units, unit.getMaxStackSize());
                    out.add(unit.copyWithCount(part));
                    units -= part;
                }
            }
            tx.commit();
        }
        return out;
    }

    private static List<ItemStack> copy(List<ItemStack> stacks) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : stacks) out.add(s.copy());
        return out;
    }

    /** The first block of the building that is not there any more (null if all are, or part of it is not loaded). */
    static @Nullable BlockPos missingMember(ServerLevel level, ControllerBlockEntity controller) {
        Placement p = controller.placement();
        if (p == null) return null;
        BuildingTemplate t = BuildingTemplate.of(p.def());
        if (t == null) return null;
        Paint paint = Paint.parse(controller.paint);
        boolean storage = p.def().logic() == Logic.STORAGE;
        BlockPos missing = null;
        for (BuildingTemplate.Cell cell : t.members) {
            BlockPos pos = p.at(cell);
            if (!level.isLoaded(pos)) return null;
            // a machine is put back by BuildingRepair instead
            if (missing == null && !BuildingRepair.machine(cell.state()) && !paint.fits(cell, level.getBlockState(pos).getBlock(), storage)) missing = pos;
        }
        return missing;
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

    /**
     * A block of the building went without the Build Gun: the whole building comes down, and the refund and its
     * contents go into a crate for its company where the controller stood ({@link #giveBack}).
     */
    public static void collapse(ServerLevel level, ControllerBlockEntity controller, BlockPos missing) {
        BlockPos at = controller.getBlockPos().immutable();
        String company = controller.companyId;
        Siftec.LOGGER.info("SIFTEC: building {} at {} broke (its block at {} is gone); it comes down and gives back its refund",
            controller.buildingId, at.toShortString(), missing.toShortString());
        giveBack(level, at, company, dismantle(level, controller));
    }

    private record Gone(ServerLevel level, Placement placement, BlockPos controller, String company, List<ItemStack> kept) {
    }

    private static final Deque<Gone> GONE = new ArrayDeque<>();

    /**
     * The controller itself was removed without the Build Gun: the rest comes down on the next tick. What the
     * controller held (its refund, input, output and buffer) is kept with it, and the factory simulation forgets it.
     */
    static void controllerGone(ServerLevel level, ControllerBlockEntity controller) {
        controller.removing = true;
        BlockPos at = controller.getBlockPos().immutable();
        Factories.removed(level, at);
        Placement p = controller.placement();
        List<ItemStack> kept = new ArrayList<>(copy(controller.refund));
        controller.refund.clear();
        kept.addAll(controller.takeContents());
        // the Converter's cell (its carrier) belongs to the machine and goes with it
        controller.carrier = ItemStack.EMPTY;
        if (p == null) {
            giveBack(level, at, controller.companyId, kept);
            return;
        }
        GONE.add(new Gone(level, p, at, controller.companyId, kept));
    }

    /**
     * What a building that came down by itself gives back: into a crate only its company's members open, so nobody
     * else picks it up (death.DeathCrates#stash); dropped where it stood when nobody owns it or no crate fits. A
     * Converter's cells, of any kind, are part of its machine and never given back.
     */
    static void giveBack(ServerLevel level, BlockPos at, String companyId, List<ItemStack> items) {
        List<ItemStack> out = new ArrayList<>(items);
        out.removeIf(s -> s.isEmpty() || Processors.cellBearing(s));
        if (out.isEmpty()) return;
        if (DeathCrates.stash(level, at, companyId, out)) return;
        Drops.all(level, at, 0.5, out);
    }

    // ------------------------------------------------------------------------------------------------ events

    public static void register() {
        load();
        ServerLifecycleEvents.SERVER_STARTED.register(BuildingIndex::serverStarted);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            BuildingIndex.serverStopped();
            GONE.clear();
            CHANGED.clear();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            while (!GONE.isEmpty()) {
                Gone g = GONE.poll();
                Siftec.LOGGER.info("SIFTEC: building {} lost its controller at {}; it comes down and gives back its refund and contents", g.placement.def().id(), g.controller.toShortString());
                List<ItemStack> items = new ArrayList<>(g.kept);
                // a new building put down where this one's controller stood (in the same tick: a command, a selftest
                // laying out its row again) has the same blocks in the same places: they are the new one's, left alone
                Set<Long> keep = new HashSet<>();
                if (g.level.getBlockEntity(g.controller) instanceof ControllerBlockEntity now && !now.removing && now.placement() != null) {
                    BuildingTemplate nt = BuildingTemplate.of(now.placement().def());
                    if (nt != null) for (BuildingTemplate.Cell cell : nt.members) keep.add(now.placement().at(cell).asLong());
                    Siftec.LOGGER.info("SIFTEC: building {} at {} was replaced before it came down; the new one's blocks stay", g.placement.def().id(), g.controller.toShortString());
                }
                items.addAll(takeDown(g.level, g.placement, g.controller, keep));
                giveBack(g.level, g.controller, g.company, items);
            }
        });
        // no block of a building can be mined, not even in creative: the Build Gun takes the building down
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            BlockPos controller = BuildingIndex.controllerOf(level, pos);
            if (controller == null) return true;
            if (player instanceof ServerPlayer server) {
                boolean hub = level.getBlockState(controller).getBlock() instanceof HubBlock;
                server.sendOverlayMessage(Component.translatable(hub ? "siftec.prefab.part" : "siftec.build_gun.part", nameAt(level, controller)));
            }
            return false;
        });
        // nor scraped, stripped or waxed: that would change the block and bring the building down
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !changesBlock(player.getItemInHand(hand))) return InteractionResult.PASS;
            if (!shellChangeRefused(level, player, hit.getBlockPos())) return InteractionResult.PASS;
            Claims.resync(player, level, hit.getBlockPos(), hit.getDirection());
            return InteractionResult.FAIL;
        });
        // nor wrenched off or turned; a wrench on a Power Pole or Tower rides its wires instead (the Zipline research)
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !player.getItemInHand(hand).is(AllItems.WRENCH)) return InteractionResult.PASS;
            BlockPos controller = BuildingIndex.controllerOf(level, hit.getBlockPos());
            if (controller == null) return InteractionResult.PASS;
            if (player instanceof ServerPlayer rider && level.getBlockEntity(controller) instanceof ControllerBlockEntity c && c.def() != null
                && c.def().power() == PowerRole.POLE) {
                BlockPos connector = connectorOf(level, c);
                if (!player.hasInfiniteMaterials() && !Companies.of(rider).hasToken("zipline")) {
                    rider.sendOverlayMessage(Component.translatable("siftec.zipline.locked"));
                } else if (connector == null || !Ziplines.start(rider, connector)) {
                    rider.sendOverlayMessage(Component.translatable("siftec.zipline.no_line"));
                }
                return InteractionResult.SUCCESS;
            }
            if (player instanceof ServerPlayer server) server.sendOverlayMessage(Component.translatable("siftec.build_gun.part", nameAt(level, controller)));
            return InteractionResult.FAIL;
        });
    }

    /** The wire connector of a building (a pole's or tower's top), or null. */
    public static @Nullable BlockPos connectorOf(Level level, ControllerBlockEntity c) {
        Placement p = c.placement();
        BuildingTemplate t = p == null ? null : BuildingTemplate.of(p.def());
        if (t == null) return null;
        for (BuildingTemplate.Cell cell : t.members) {
            if (level.getBlockEntity(p.at(cell)) instanceof AbstractElectricalConnector) return p.at(cell);
        }
        return null;
    }

    static Component nameAt(Level level, BlockPos controller) {
        if (level.getBlockEntity(controller) instanceof ControllerBlockEntity c && def(c.buildingId) != null) return def(c.buildingId).name();
        // the HUB's Craft Station is listed under the HUB's terminal
        if (level.getBlockState(controller).getBlock() instanceof HubBlock) return Component.translatable("block.siftec.hub");
        return Component.translatable("siftec.build_gun.building");
    }

    /**
     * True (and the player told why) if a block of a Build Gun building may not be changed by hand: an axe scraping or
     * stripping it, wax going on or off, a casing applied to it. Changing one would bring the building down; its shell
     * changes only with the Build Gun and the Customizer. The controller and the ports are left to their own use.
     */
    public static boolean shellChangeRefused(Level level, @Nullable Player player, BlockPos pos) {
        BlockPos controller = BuildingIndex.controllerOf(level, pos);
        if (controller == null || pos.equals(controller) || level.getBlockState(pos).getBlock() instanceof PortBlock) return false;
        if (player instanceof ServerPlayer server) server.sendOverlayMessage(Component.translatable("siftec.build_gun.part", nameAt(level, controller)));
        return true;
    }

    /** Items whose use on a block changes that block (an axe strips or scrapes, honeycomb waxes, a shovel or hoe turns soil). */
    static boolean changesBlock(ItemStack stack) {
        return stack.is(ItemTags.AXES) || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES)
            || stack.is(Items.HONEYCOMB);
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

    /** How far {@link #within} looks out from a spot for the building round it. */
    private static final int WITHIN_REACH = 32;

    /**
     * True if the spot is a block of a Build Gun building or lies inside one's space: its room, a doorway, the air its
     * template keeps free (those are no blocks of it, so {@link #protectedAt} does not see them). Looks out along the six
     * directions for the first block of a building and checks that building's box.
     */
    public static boolean within(Level level, BlockPos pos) {
        if (BuildingIndex.isMember(level, pos)) return true;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) {
            for (int i = 1; i <= WITHIN_REACH; i++) {
                at.setWithOffset(pos, d.getStepX() * i, d.getStepY() * i, d.getStepZ() * i);
                BlockPos controller = BuildingIndex.controllerOf(level, at);
                if (controller == null) continue;
                // a Blueprint Designer's volume is no part of it: whatever is built there is the players' own
                if (Chunks.blockEntity(level, controller) instanceof ControllerBlockEntity c
                    && BuildGun.bounds(c).contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) && !inOpenSpace(c, pos)) return true;
                break;
            }
        }
        return false;
    }

    /** True if the spot is in the open space of a building that is no part of it (a Blueprint Designer's volume). */
    static boolean inOpenSpace(ControllerBlockEntity c, BlockPos pos) {
        AABB open = DesignerBlockEntity.openSpace(c);
        return open != null && open.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /** For the protections in mixins: true if the block belongs to a building (always false on the client). */
    public static boolean protectedAt(Level level, BlockPos pos) {
        return BuildingIndex.isMember(level, pos);
    }

    /**
     * True if something at {@code from} (a funnel, a hopper) may not reach the inventory at {@code target}: the target is
     * a block of a building other than one of its ports, and {@code from} is not part of that same building. A building
     * is fed and emptied only at its ports, as in Satisfactory.
     */
    public static boolean sealed(Level level, BlockPos from, BlockPos target) {
        BlockPos building = BuildingIndex.controllerOf(level, target);
        // Build Gun buildings only: the HUB's hand-cranked Craft Station is listed under its terminal and stays open
        if (building == null || level.getBlockState(target).getBlock() instanceof PortBlock || !(level.getBlockEntity(building) instanceof ControllerBlockEntity)) return false;
        return !building.equals(BuildingIndex.controllerOf(level, from));
    }

    /**
     * Whether a Deployer standing at {@code deployer} may act on the block at {@code target}: never on a building's
     * block, unless the Deployer is part of that same building (the Manufacturer's line works on its own Depots).
     */
    public static boolean deployerMayAct(Level level, BlockPos deployer, BlockPos target) {
        BlockPos building = BuildingIndex.controllerOf(level, target);
        if (building == null) return true;
        return building.equals(BuildingIndex.controllerOf(level, deployer));
    }
}
