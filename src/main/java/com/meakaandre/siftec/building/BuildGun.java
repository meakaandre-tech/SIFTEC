package com.meakaandre.siftec.building;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.hub.HubBlockEntity;
import com.meakaandre.siftec.prefab.Prefabs;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

import java.util.HashMap;
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
    }

    private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();
    private static final Map<UUID, BuildGunPayloads.View> SENT = new HashMap<>();
    /** What players built and took down with the gun lately ("placed <id> x y z", "dismantled <id>"), for the client selftest. */
    public static final java.util.List<String> USES = new java.util.ArrayList<>();

    private static void used(String line) {
        if (USES.size() >= 64) USES.removeFirst();
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
        Direction facing = player.getDirection();
        for (int i = turns & 3; i > 0; i--) facing = facing.getClockWise();
        // a miner snaps onto the node it is aimed near, so its drill stands right over it
        return Buildings.snapToNode(player.level(), new Placement(def, anchor.immutable(), facing, flip));
    }

    /** A client's hologram spot is believed only near the player (it may be nudged a little past the aim). */
    private static boolean reachable(Player player, Placement p) {
        return p.anchor().distToCenterSqr(player.getEyePosition()) <= (Buildings.RANGE + 16) * (Buildings.RANGE + 16);
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
    static void act(ServerPlayer player, Company company) {
        Selection sel = selection(player);
        ServerLevel level = player.level();
        if (sel.dismantle) {
            BlockHitResult hit = aimed(player);
            ControllerBlockEntity controller = hit == null ? null : controllerAt(level, hit.getBlockPos());
            HubBlockEntity hub = controller != null || hit == null ? null : Prefabs.hubAt(level, hit.getBlockPos());
            if (hub != null) {
                takeDownHub(player, hub, company);
                return;
            }
            if (controller == null) {
                player.sendOverlayMessage(Component.translatable("siftec.build_gun.dismantle_aim"));
                return;
            }
            Component refused = mayDismantle(player, controller);
            if (refused != null) {
                player.sendOverlayMessage(refused);
                return;
            }
            Component name = Buildings.nameAt(level, controller.getBlockPos());
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
        if (def.hub()) {
            // the HUB goes down as its structure (prefab/Prefabs.java), built in parts as its upgrades are done; its 20 Raw
            // Iron is taken from the player and comes back when it is dismantled
            Prefabs.Check check = Prefabs.place(level, hubSpot(p), company, player);
            ok = check.ok();
            message = ok ? Component.translatable("siftec.prefab.placed", def.name()) : check.message();
        } else {
            Buildings.Check check = Buildings.place(level, p, company, player.getInventory(), player.hasInfiniteMaterials());
            ok = check.ok();
            message = ok ? Component.translatable("siftec.prefab.placed", def.name()) : check.message();
        }
        if (ok) used("placed " + def.id() + " " + p.anchor().getX() + " " + p.anchor().getY() + " " + p.anchor().getZ());
        // the HUB says itself what land it claimed
        if (!ok || !def.hub()) player.sendOverlayMessage(message);
        if (!ok) player.containerMenu.broadcastFullState();
    }

    /** Dismantles a HUB for a player of its company: its 20 Raw Iron and everything in it come back. False if refused. */
    static boolean takeDownHub(ServerPlayer player, HubBlockEntity hub, Company company) {
        Component refused = player.hasInfiniteMaterials() || hub.companyId.isEmpty() || company.id.equals(hub.companyId) ? null
            : Component.translatable("siftec.build_gun.not_yours");
        if (refused != null) {
            player.sendOverlayMessage(refused);
            return false;
        }
        ServerLevel level = player.level();
        Prefabs.give(level, player, hub.getBlockPos(), Prefabs.dismantle(level, hub));
        used("dismantled hub");
        player.sendOverlayMessage(Component.translatable("siftec.build_gun.dismantled", Component.translatable("block.siftec.hub")));
        return true;
    }

    /** Where the HUB structure goes for a Build Gun placement: the same front edge and facing (it cannot be flipped). */
    public static Prefabs.Spot hubSpot(Placement p) {
        return new Prefabs.Spot(Prefabs.Kind.HUB, p.anchor(), p.facing());
    }

    /** The server's verdict on the HUB at a spot: the structure's own check, the land rules, and the 20 Raw Iron. */
    static Prefabs.Check hubCheck(ServerPlayer player, Placement p) {
        return hubCheck(player, p, Companies.of(player));
    }

    static Prefabs.Check hubCheck(ServerPlayer player, Placement p, Company company) {
        boolean creative = player.hasInfiniteMaterials();
        Prefabs.Check check = Prefabs.check(player.level(), hubSpot(p), company, creative);
        if (check.ok() && !creative) {
            var missing = Prefabs.missing(player, Prefabs.hubCost());
            if (missing != null) check.fail("siftec.prefab.cost", missing.count(), missing.icon().getHoverName());
        }
        return check;
    }

    public static @Nullable ControllerBlockEntity controllerAt(Level level, BlockPos pos) {
        BlockPos c = BuildingIndex.controllerOf(level, pos);
        if (c == null && level.getBlockEntity(pos) instanceof ControllerBlockEntity own) return own;
        return c != null && level.getBlockEntity(c) instanceof ControllerBlockEntity controller ? controller : null;
    }

    /** Null if the player may take the building down: their company's, nobody's, or they are in creative. */
    static @Nullable Component mayDismantle(ServerPlayer player, ControllerBlockEntity controller) {
        return mayDismantle(player, controller.companyId);
    }

    static @Nullable Component mayDismantle(ServerPlayer player, String companyId) {
        if (player.hasInfiniteMaterials() || companyId == null || companyId.isEmpty()) return null;
        Company mine = Companies.of(player);
        if (mine.id.equals(companyId)) return null;
        return Component.translatable("siftec.build_gun.not_yours");
    }

    // ------------------------------------------------------------------------------------------------ the hologram's verdict

    private static void tick(net.minecraft.server.MinecraftServer server) {
        if (server.getTickCount() % 4 != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!holding(player)) {
                SENT.remove(player.getUUID());
                continue;
            }
            Selection sel = selection(player);
            BuildGunPayloads.View view;
            if (sel.dismantle) {
                BlockHitResult hit = aimed(player);
                ControllerBlockEntity controller = hit == null ? null : controllerAt(player.level(), hit.getBlockPos());
                HubBlockEntity hub = controller != null || hit == null ? null : Prefabs.hubAt(player.level(), hit.getBlockPos());
                Placement p = controller == null ? null : controller.placement();
                BuildingTemplate t = p == null ? null : BuildingTemplate.of(p.def());
                if (hub != null) {
                    AABB box = Prefabs.bounds(new Prefabs.Spot(Prefabs.Kind.HUB, hub.anchor, hub.facing));
                    Component refused = mayDismantle(player, hub.companyId);
                    view = BuildGunPayloads.View.of(refused == null, refused != null ? refused
                            : Component.translatable("siftec.build_gun.dismantle_hint", Component.translatable("block.siftec.hub")),
                        BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX - 1, box.maxY - 1, box.maxZ - 1));
                } else if (p == null || t == null) {
                    view = BuildGunPayloads.View.of(false, Component.translatable("siftec.build_gun.dismantle_aim"), null, null);
                } else {
                    AABB box = p.bounds(t);
                    Component refused = mayDismantle(player, controller);
                    view = BuildGunPayloads.View.of(refused == null, refused != null ? refused : Component.translatable("siftec.build_gun.dismantle_hint", p.def().name()),
                        BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX - 1, box.maxY - 1, box.maxZ - 1));
                }
            } else {
                Placement p = sel.spot;
                if (p == null || !reachable(player, p)) continue;
                if (p.def().hub()) {
                    Prefabs.Check check = hubCheck(player, p);
                    view = BuildGunPayloads.View.of(check.ok(), check.ok() ? Component.translatable("siftec.build_gun.fits_hub") : check.message(), p.anchor(), p.anchor());
                } else {
                    Buildings.Check check = Buildings.check(player.level(), p, Companies.of(player), player.getInventory(), player.hasInfiniteMaterials());
                    view = BuildGunPayloads.View.of(check.ok(), check.message(), p.anchor(), p.anchor());
                }
            }
            if (view.equals(SENT.get(player.getUUID()))) continue;
            SENT.put(player.getUUID(), view);
            ServerPlayNetworking.send(player, view);
        }
    }

    // ------------------------------------------------------------------------------------------------ events

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(BuildGunPayloads.Select.TYPE, BuildGunPayloads.Select.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BuildGunPayloads.View.TYPE, BuildGunPayloads.View.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BuildGunPayloads.Tab.TYPE, BuildGunPayloads.Tab.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(BuildGunPayloads.Select.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            Selection sel = selection(player);
            sel.building = payload.building();
            sel.dismantle = payload.dismantle();
            BuildingDef def = Buildings.def(payload.building());
            sel.spot = def != null && payload.hasSpot() ? new Placement(def, payload.anchor(), Direction.from2DDataValue(payload.facing()), payload.flip()) : null;
        });
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

    /** The building a selftest picks, as a client would (the selftest has no client). */
    static void select(Player player, String building, boolean dismantle, @Nullable Placement spot) {
        Selection sel = selection(player);
        sel.building = building;
        sel.dismantle = dismantle;
        sel.spot = spot;
    }

}
