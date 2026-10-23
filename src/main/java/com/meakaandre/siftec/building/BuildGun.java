package com.meakaandre.siftec.building;

import com.meakaandre.siftec.backpack.Carried;
import com.meakaandre.siftec.claim.Claims;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.hub.HubBlockEntity;
import com.meakaandre.siftec.prefab.Prefabs;
import com.meakaandre.siftec.vehicle.VehicleEntity;
import com.meakaandre.siftec.vehicle.Vehicles;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Build Gun's server side: what each player has picked (sent by their client), building or dismantling when
 * they use the gun, and the verdict their hologram is coloured by.
 */
public final class BuildGun {
    private BuildGun() {
    }

    /** What a player's gun is set to, as their client last said. */
    public static final class Selection {
        public String building = "";
        public boolean dismantle;
        public @Nullable Placement spot;
        /** Where the client's hologram was before the server snapped it onto a node (the spot it sent). */
        public @Nullable BlockPos aimed;
    }

    private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();
    private static final Map<UUID, BuildGunPayloads.View> SENT = new HashMap<>();
    /** What players built and took down with the gun lately ("placed <id> x y z", "dismantled <id>"), for the client selftest. */
    public static final List<String> USES = new ArrayList<>();
    /** How many of the latest {@link #USES} are kept. */
    private static final int MAX_USES = 64;
    /** Placements snap to a grid of this many blocks. */
    private static final int GRID = 8;
    /** How far past {@link Buildings#RANGE} a client's hologram spot is still believed, in blocks. */
    private static final int HOLOGRAM_SLACK = 16;
    /** How often each holding player's hologram verdict is worked out again, in ticks. */
    private static final int VIEW_INTERVAL_TICKS = 4;

    private static void used(String line) {
        if (USES.size() >= MAX_USES) USES.removeFirst();
        USES.add(line);
    }

    public static Selection selection(Player player) {
        return SELECTIONS.computeIfAbsent(player.getUUID(), k -> new Selection());
    }

    public static boolean holding(Player player) {
        return player.getMainHandItem().getItem() instanceof BuildGunItem;
    }

    // ------------------------------------------------------------------------------------------------ aiming

    /** The block the player aims at within the gun's range, or null. */
    public static @Nullable BlockHitResult aimed(Player player) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1f).scale(Buildings.RANGE));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    /**
     * Where a building goes for what the player aims at: the middle of its front edge on the block aimed at (on its
     * top face, or next to the face aimed at), stretching away from the player, turned by {@code turns} quarters.
     */
    public static @Nullable Placement aim(Player player, BuildingDef def, int turns, boolean flip) {
        BlockHitResult hit = aimed(player);
        if (hit == null) return null;
        BlockPos anchor = hit.getDirection() == Direction.UP ? hit.getBlockPos().above() : hit.getBlockPos().relative(hit.getDirection());
        // a Water Extractor stands in the water: aimed at the surface, its bottom row is the water's top layer
        if (def.intake() != null) {
            Level level = player.level();
            Vec3 eye = player.getEyePosition();
            BlockHitResult wet = level.clip(new ClipContext(eye, eye.add(player.getViewVector(1f).scale(Buildings.RANGE)), ClipContext.Block.OUTLINE,
                ClipContext.Fluid.SOURCE_ONLY, player));
            if (wet.getType() == HitResult.Type.BLOCK && level.getFluidState(wet.getBlockPos()).is(FluidTags.WATER)) {
                anchor = wet.getBlockPos();
            }
        }
        Direction facing = player.getDirection();
        for (int i = turns & 3; i > 0; i--) facing = facing.getClockWise();
        Placement p = new Placement(def, anchor.immutable(), facing, flip);
        // foundations, walls and roofs snap to an 8-block grid, so they line up with each other
        if (def.logic() == Logic.FOUNDATION && !def.id().startsWith("billboard_")) p = snapToGrid(p);
        // a miner snaps onto the node it is aimed near, so its drill stands right over it
        return BuildingNodes.snapToNode(player.level(), p);
    }

    /** The placement moved (by up to 4 blocks either way) so its corner is on the 8-block grid. */
    public static Placement snapToGrid(Placement p) {
        BuildingTemplate t = BuildingTemplate.of(p.def());
        if (t == null) return p;
        BlockPos a = p.at(t.minX, 0, 0), b = p.at(t.maxX, 0, t.depth - 1);
        int x = Math.min(a.getX(), b.getX()), z = Math.min(a.getZ(), b.getZ());
        // the nearest multiple of 8 in whole numbers (float division loses blocks far from the world's middle)
        int dx = Math.floorDiv(x + GRID / 2, GRID) * GRID - x, dz = Math.floorDiv(z + GRID / 2, GRID) * GRID - z;
        return new Placement(p.def(), p.anchor().offset(dx, 0, dz), p.facing(), p.flip());
    }

    /** A client's hologram spot is believed only near the player (it may be nudged a little past the aim). */
    private static boolean reachable(Player player, Placement p) {
        return p.anchor().distToCenterSqr(player.getEyePosition())
            <= (Buildings.RANGE + HOLOGRAM_SLACK) * (Buildings.RANGE + HOLOGRAM_SLACK);
    }

    // ------------------------------------------------------------------------------------------------ using

    /** The gun was used (right click): build at the hologram, or in dismantle mode take down the building aimed at. */
    public static void use(ServerPlayer player) {
        if (Companies.isFake(player)) return;
        act(player);
    }

    static void act(ServerPlayer player) {
        act(player, Companies.of(player));
    }

    /** What a use does, for a player of a company (the selftests use a fake player for their own company). */
    public static void act(ServerPlayer player, Company company) {
        Selection sel = selection(player);
        ServerLevel level = player.level();
        if (sel.dismantle) {
            // a vehicle in the way is what the gun points at first
            VehicleEntity vehicle = Vehicles.aimed(player);
            if (vehicle != null) {
                Component refused = mayDismantle(player, company, vehicle.companyId, vehicle.getBoundingBox());
                if (refused != null) {
                    player.sendOverlayMessage(refused);
                    return;
                }
                Component name = vehicle.getName();
                Prefabs.give(level, player, vehicle.blockPosition(), Vehicles.dismantle(vehicle));
                used("dismantled " + vehicle.kind.id);
                player.sendOverlayMessage(Component.translatable("siftec.build_gun.dismantled", name));
                return;
            }
            BlockHitResult hit = aimed(player);
            ControllerBlockEntity controller = hit == null ? null : controllerAt(level, hit.getBlockPos());
            HubBlockEntity hub = controller != null || hit == null ? null : Prefabs.structureAt(level, hit.getBlockPos());
            if (hub != null) {
                takeDownHub(player, hub, company);
                return;
            }
            if (controller == null) {
                player.sendOverlayMessage(Component.translatable("siftec.build_gun.dismantle_aim"));
                return;
            }
            Component refused = mayDismantle(player, company, controller);
            if (refused != null) {
                player.sendOverlayMessage(refused);
                return;
            }
            Component name = BuildingProtection.nameAt(level, controller.getBlockPos());
            String id = controller.buildingId;
            Prefabs.give(level, player, controller.getBlockPos(), Buildings.dismantle(level, controller));
            used("dismantled " + id);
            player.sendOverlayMessage(Component.translatable("siftec.build_gun.dismantled", name));
            return;
        }
        BuildingDef def = Buildings.def(sel.building);
        if (def == null) {
            player.sendOverlayMessage(Component.translatable("siftec.build_gun.pick"));
            return;
        }
        Placement p = sel.spot != null && sel.spot.def() == def && reachable(player, sel.spot) ? sel.spot : aim(player, def, 0, false);
        if (p == null) {
            player.sendOverlayMessage(Component.translatable("siftec.prefab.aim", Buildings.RANGE));
            return;
        }
        boolean ok;
        Component message;
        if (def.vehicle()) {
            // a vehicle goes down as one entity (vehicle/Vehicles.java); its cost comes back when it is taken back
            Buildings.Check check = Vehicles.place(level, p, company, Carried.of(player), player.hasInfiniteMaterials());
            ok = check.ok();
            message = ok ? Component.translatable("siftec.prefab.placed", def.name()) : check.message();
        } else if (def.structure()) {
            // the HUB and the Wormhole Gateway go down as their structures (prefab/Prefabs.java), built in parts as their
            // upgrades or phases are done; their cost is taken from the player and comes back when they are dismantled
            Prefabs.Check check = hubCheck(player, p, company);
            if (check.ok()) check = Prefabs.place(level, hubSpot(p), company, player);
            ok = check.ok();
            message = ok ? Component.translatable("siftec.prefab.placed", def.name()) : check.message();
        } else {
            Buildings.Check check = Buildings.place(level, p, company, Carried.of(player), player.hasInfiniteMaterials());
            ok = check.ok();
            message = ok ? Component.translatable("siftec.prefab.placed", def.name()) : check.message();
        }
        if (ok) used("placed " + def.id() + " " + p.anchor().getX() + " " + p.anchor().getY() + " " + p.anchor().getZ());
        // the HUB says itself what land it claimed
        if (!ok || !def.hub()) player.sendOverlayMessage(message);
        if (!ok) player.containerMenu.broadcastFullState();
    }

    /** Dismantles a HUB or Wormhole Gateway for a player of its company: its cost and everything in it come back. False if refused. */
    static boolean takeDownHub(ServerPlayer player, HubBlockEntity hub, Company company) {
        Component refused = mayDismantle(player, company, hub.companyId, hubBounds(hub));
        if (refused != null) {
            player.sendOverlayMessage(refused);
            return false;
        }
        ServerLevel level = player.level();
        String key = hub.gateway() ? "block.siftec.wormhole_gateway" : "block.siftec.hub";
        Prefabs.give(level, player, hub.getBlockPos(), Prefabs.dismantle(level, hub));
        used(hub.gateway() ? "dismantled gateway" : "dismantled hub");
        player.sendOverlayMessage(Component.translatable("siftec.build_gun.dismantled", Component.translatable(key)));
        return true;
    }

    /** Where a structure (the HUB, the Gateway) goes for a Build Gun placement: the same front edge and facing (it cannot be flipped). */
    public static Prefabs.Spot hubSpot(Placement p) {
        return new Prefabs.Spot(p.def().structure() ? p.def().kind() : Prefabs.Kind.HUB, p.anchor(), p.facing());
    }

    /** The server's verdict on a structure at a spot: the structure's own check, the land rules, its unlock and its cost. */
    static Prefabs.Check hubCheck(ServerPlayer player, Placement p) {
        return hubCheck(player, p, Companies.of(player));
    }

    static Prefabs.Check hubCheck(ServerPlayer player, Placement p, Company company) {
        boolean creative = player.hasInfiniteMaterials();
        Prefabs.Check check = Prefabs.check(player.level(), hubSpot(p), company, creative);
        if (check.ok() && !creative && Buildings.locked(p.def(), company)) check.fail("siftec.build_gun.locked", Buildings.unlockName(p.def()));
        if (check.ok() && !creative) {
            var missing = Prefabs.missing(player, Prefabs.cost(hubSpot(p).kind()));
            if (missing != null) check.fail(p.def().hub() ? "siftec.prefab.cost" : "siftec.prefab.cost_any", missing.count(), missing.icon().getHoverName());
        }
        return check;
    }

    public static @Nullable ControllerBlockEntity controllerAt(Level level, BlockPos pos) {
        BlockPos c = BuildingIndex.controllerOf(level, pos);
        if (c == null && level.getBlockEntity(pos) instanceof ControllerBlockEntity own) return own;
        return c != null && level.getBlockEntity(c) instanceof ControllerBlockEntity controller ? controller : null;
    }

    /** The space a HUB or Wormhole Gateway takes up, as it stands. */
    static AABB hubBounds(HubBlockEntity hub) {
        return Prefabs.bounds(new Prefabs.Spot(hub.gateway() ? Prefabs.Kind.GATEWAY : Prefabs.Kind.HUB, hub.anchor, hub.facing));
    }

    /** The space a placed building takes up, or just its controller's block if its placement is lost. */
    public static AABB bounds(ControllerBlockEntity controller) {
        Placement p = controller.placement();
        BuildingTemplate t = p == null ? null : BuildingTemplate.of(p.def());
        return p != null && t != null ? p.bounds(t) : new AABB(controller.getBlockPos());
    }

    static @Nullable Component mayDismantle(ServerPlayer player, Company company, ControllerBlockEntity controller) {
        return mayDismantle(player, company, controller.companyId, bounds(controller));
    }

    /**
     * Null if a player of this company may take down a building (or HUB) owned by {@code ownerId} that takes up
     * {@code box}; otherwise why not. A company's building comes down only for its own members, wherever it stands
     * (its refund is theirs). One nobody owns comes down for anyone the land lets build there: unclaimed land, or
     * their own company's claim. Creative players may take down anything.
     */
    public static @Nullable Component mayDismantle(ServerPlayer player, Company company, @Nullable String ownerId, AABB box) {
        if (player.hasInfiniteMaterials()) return null;
        if (ownerId != null && !ownerId.isEmpty()) {
            return Companies.owns(company, ownerId) ? null : Component.translatable("siftec.build_gun.not_yours");
        }
        Claims.Claim claim = Claims.foreign(player.level(), box, company.id);
        return claim == null ? null : Component.translatable("siftec.claim.denied", Claims.holder(player.level(), claim));
    }

    // ------------------------------------------------------------------------------------------------ the hologram's verdict

    private static void tick(MinecraftServer server) {
        if (server.getTickCount() % VIEW_INTERVAL_TICKS != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!holding(player)) {
                SENT.remove(player.getUUID());
                continue;
            }
            BuildGunPayloads.View view = view(player);
            if (view == null) continue;
            if (view.equals(SENT.get(player.getUUID()))) continue;
            SENT.put(player.getUUID(), view);
            ServerPlayNetworking.send(player, view);
        }
    }

    /** What a player's hologram should show now (see {@link BuildGunPayloads.View}), or null if there is nothing to say. */
    public static BuildGunPayloads.@Nullable View view(ServerPlayer player) {
        Selection sel = selection(player);
        BuildGunPayloads.View view;
        VehicleEntity vehicle = sel.dismantle ? Vehicles.aimed(player) : null;
        if (vehicle != null) {
            AABB box = vehicle.getBoundingBox();
            Component refused = mayDismantle(player, Companies.of(player), vehicle.companyId, box);
            view = BuildGunPayloads.View.of(refused == null, refused != null ? refused : Component.translatable("siftec.build_gun.dismantle_vehicle", vehicle.getName()),
                BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX - Buildings.EDGE_EPSILON, box.maxY - Buildings.EDGE_EPSILON, box.maxZ - Buildings.EDGE_EPSILON));
        } else if (sel.dismantle) {
            BlockHitResult hit = aimed(player);
            ControllerBlockEntity controller = hit == null ? null : controllerAt(player.level(), hit.getBlockPos());
            HubBlockEntity hub = controller != null || hit == null ? null : Prefabs.structureAt(player.level(), hit.getBlockPos());
            Placement p = controller == null ? null : controller.placement();
            BuildingTemplate t = p == null ? null : BuildingTemplate.of(p.def());
            if (hub != null) {
                AABB box = hubBounds(hub);
                Component refused = mayDismantle(player, Companies.of(player), hub.companyId, box);
                view = BuildGunPayloads.View.of(refused == null, refused != null ? refused
                        : Component.translatable("siftec.build_gun.dismantle_hint", Component.translatable(hub.gateway() ? "block.siftec.wormhole_gateway" : "block.siftec.hub")),
                    BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX - 1, box.maxY - 1, box.maxZ - 1));
            } else if (p == null || t == null) {
                view = BuildGunPayloads.View.of(false, Component.translatable("siftec.build_gun.dismantle_aim"), null, null);
            } else {
                AABB box = p.bounds(t);
                Component refused = mayDismantle(player, Companies.of(player), controller.companyId, box);
                view = BuildGunPayloads.View.of(refused == null, refused != null ? refused : Component.translatable("siftec.build_gun.dismantle_hint", p.def().name()),
                    BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX - 1, box.maxY - 1, box.maxZ - 1));
            }
        } else {
            Placement p = sel.spot;
            if (p == null || !reachable(player, p)) return null;
            if (p.def().vehicle()) {
                Buildings.Check check = Vehicles.check(player.level(), p, Companies.of(player), Carried.of(player), player.hasInfiniteMaterials());
                view = BuildGunPayloads.View.of(check.ok(), check.ok() ? Component.translatable("siftec.build_gun.fits_vehicle", p.def().name()) : check.message(),
                    p.anchor(), p.anchor());
            } else if (p.def().structure()) {
                Prefabs.Check check = hubCheck(player, p);
                view = BuildGunPayloads.View.of(check.ok(), check.ok() ? Component.translatable(p.def().hub() ? "siftec.build_gun.fits_hub" : "siftec.build_gun.fits_gateway")
                    : check.message(), p.anchor(), p.anchor());
            } else {
                Buildings.Check check = Buildings.check(player.level(), p, Companies.of(player), Carried.of(player), player.hasInfiniteMaterials());
                // where it really goes (a miner snapped onto its node), and the spot the client sent, so it can move its hologram there
                view = BuildGunPayloads.View.of(check.ok(), check.message(), p.anchor(), p.anchor()).from(sel.aimed == null ? p.anchor() : sel.aimed);
            }
        }
        return view;
    }

    // ------------------------------------------------------------------------------------------------ events

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(BuildGunPayloads.Select.TYPE, BuildGunPayloads.Select.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BuildGunPayloads.View.TYPE, BuildGunPayloads.View.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(BuildGunPayloads.Select.TYPE, (payload, context) -> selected(context.player(), payload));
        // the gun works on whatever it points at, so using it on a block never opens or uses that block
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (!(stack.getItem() instanceof BuildGunItem)) return InteractionResult.PASS;
            if (player instanceof ServerPlayer server) use(server);
            return InteractionResult.SUCCESS;
        });
        // nor does it mine: left click is the gun's cancel
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> holding(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        ServerTickEvents.END_SERVER_TICK.register(BuildGun::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            USES.clear();
            SELECTIONS.clear();
            SENT.clear();
        });
    }

    /**
     * What a client said it picked and where its hologram is. Only the server knows where the ore nodes are, so a miner's
     * spot is snapped onto the node near it here ({@link BuildingNodes#snapToNode}); the snapped spot is what is built and
     * what the client is told ({@link BuildGunPayloads.View#from}), so its hologram moves onto the node too.
     */
    public static void selected(ServerPlayer player, BuildGunPayloads.Select payload) {
        Selection sel = selection(player);
        sel.building = payload.building();
        sel.dismantle = payload.dismantle();
        BuildingDef def = Buildings.def(payload.building());
        Placement spot = def != null && payload.hasSpot() ? new Placement(def, payload.anchor(), Direction.from2DDataValue(payload.facing()), payload.flip()) : null;
        sel.aimed = spot == null ? null : spot.anchor();
        sel.spot = spot == null ? null : BuildingNodes.snapToNode(player.level(), spot);
    }

    /** The building a selftest picks, as a client would (the selftest has no client). */
    public static void select(Player player, String building, boolean dismantle, @Nullable Placement spot) {
        Selection sel = selection(player);
        sel.building = building;
        sel.dismantle = dismantle;
        sel.spot = spot;
    }

}
