package com.meakaandre.siftec.client;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildGun;
import com.meakaandre.siftec.building.BuildGunItem;
import com.meakaandre.siftec.building.BuildGunPayloads;
import com.meakaandre.siftec.building.BuildHotbars;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.BuildingTemplate;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.Placement;
import com.meakaandre.siftec.prefab.Prefabs;
import com.meakaandre.siftec.prefab.Template;
import com.meakaandre.siftec.vehicle.VehicleEntity;
import com.meakaandre.siftec.vehicle.VehicleKind;
import com.meakaandre.siftec.vehicle.Vehicles;
import com.mojang.blaze3d.platform.InputConstants;
import com.zurrtum.create.client.AllSpecialTextures;
import com.zurrtum.create.client.catnip.outliner.Outliner;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Build Gun on the client, worked like Satisfactory 1.0's (see {@link #register} for the keys): the build hotbars
 * and the build menu pick what the hologram is, left click builds it ({@link BuildGunPayloads.Place}), and in
 * dismantle mode left click held for a second takes down what is aimed at or selected ({@link BuildGunPayloads.Dismantle}).
 * The server checks everything again; this class only reads the keys and draws the hologram. The HUD is
 * {@link BuildGunHud}, the menu {@link BuildMenuScreen}.
 */
public final class BuildGunClient {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Siftec.id("build_gun"));

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.siftec.build_gun." + name, code, CATEGORY);
    }

    private static KeyMapping mouse(String name, int button) {
        return new KeyMapping("key.siftec.build_gun." + name, InputConstants.Type.MOUSE, button, CATEGORY);
    }

    public static final KeyMapping MENU = key("menu", InputConstants.KEY_Q);
    public static final KeyMapping PLACE = mouse("place", InputConstants.MOUSE_BUTTON_LEFT);
    public static final KeyMapping CANCEL = mouse("cancel", InputConstants.MOUSE_BUTTON_RIGHT);
    public static final KeyMapping SAMPLE = mouse("sample", InputConstants.MOUSE_BUTTON_MIDDLE);
    public static final KeyMapping ALT = key("alt", InputConstants.KEY_LALT);
    public static final KeyMapping MIRROR = key("mirror", InputConstants.KEY_R);
    public static final KeyMapping LOCK = key("lock", InputConstants.KEY_H);
    public static final KeyMapping NUDGE_FORWARD = key("nudge_forward", InputConstants.KEY_UP);
    public static final KeyMapping NUDGE_BACK = key("nudge_back", InputConstants.KEY_DOWN);
    public static final KeyMapping NUDGE_LEFT = key("nudge_left", InputConstants.KEY_LEFT);
    public static final KeyMapping NUDGE_RIGHT = key("nudge_right", InputConstants.KEY_RIGHT);
    public static final KeyMapping NUDGE_UP = key("nudge_up", InputConstants.KEY_PAGEUP);
    public static final KeyMapping NUDGE_DOWN = key("nudge_down", InputConstants.KEY_PAGEDOWN);
    public static final KeyMapping DISMANTLE = key("dismantle", InputConstants.KEY_F);
    public static final KeyMapping MULTI = key("multi", InputConstants.KEY_LCONTROL);
    public static final KeyMapping FILTER = key("filter", InputConstants.KEY_G);
    /** Build hotbar slots 1 to 10, on the keys 1 to 9 and 0. */
    public static final KeyMapping[] SLOTS = new KeyMapping[BuildHotbars.SLOTS];

    static {
        int[] codes = {InputConstants.KEY_1, InputConstants.KEY_2, InputConstants.KEY_3, InputConstants.KEY_4, InputConstants.KEY_5,
            InputConstants.KEY_6, InputConstants.KEY_7, InputConstants.KEY_8, InputConstants.KEY_9, InputConstants.KEY_0};
        for (int i = 0; i < SLOTS.length; i++) SLOTS[i] = key("slot_" + (i + 1), codes[i]);
    }

    private static final Object SHAPE = "siftec:build_gun_shape", BLOCKED = "siftec:build_gun_blocked", TARGET = "siftec:build_gun_target";
    /** The twelve edges of a vehicle's turned body (corner indices from VehicleEntity.bodyCorners), each its own outline. */
    private static final int[][] EDGES = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
    private static final Object[] VEHICLE_EDGE = new Object[EDGES.length];
    private static final Object[] SELECTED_BOX = new Object[BuildGunPayloads.MAX_DISMANTLE];

    static {
        for (int i = 0; i < EDGES.length; i++) VEHICLE_EDGE[i] = "siftec:build_gun_vehicle_" + i;
        for (int i = 0; i < SELECTED_BOX.length; i++) SELECTED_BOX[i] = "siftec:build_gun_selected_" + i;
    }

    /** Satisfactory's hologram colours: blue fits, red is blocked; dismantle highlights in its orange-red. */
    static final int BLUE = 0x4AA8FF, RED = 0xE03C3C, DISMANTLE_COLOR = 0xFF6A2A, REFUSED = 0x9A9A9A;
    /** How far a vehicle's outline stands off its body (blocks). */
    private static final double VEHICLE_OUTLINE_GROW = 0.05;
    /** Ticks left click is held to take down (Satisfactory's one second). */
    static final int HOLD_TICKS = 20;

    /** One building picked for a mass dismantle: a block of it, its id, and its box for the outline. */
    record Picked(BlockPos at, String id, AABB box) {
    }

    // ---------------------------------------------------------------- state

    /** The building the hologram is ("" for none). */
    static String picked = "";
    static boolean dismantle;
    /** H: the hologram stays where it is and the arrow keys and Page Up/Down move it. */
    static boolean locked;
    static @Nullable Placement frozen;
    static boolean flip;
    static int turns;
    /** The build hotbars, bar after bar, and the bar and slot shown (slot -1: none picked from the bar). */
    static final List<String> bars = new ArrayList<>(Collections.nCopies(BuildHotbars.BARS * BuildHotbars.SLOTS, ""));
    static int page, slot = -1;
    /** What the server last said the build menu shows and the player carries ({@link BuildGunPayloads.GunState}). */
    static Set<String> unlocked = new HashSet<>();
    static Map<String, Integer> carried = new HashMap<>();
    /** Dismantle mode: what Ctrl selected, and the G filter ("" for none). */
    static final List<Picked> selection = new ArrayList<>();
    static String filter = "";
    /** Ticks left click has been held in dismantle mode (-1: done, waiting for it to be let go). */
    static int held;
    static @Nullable Placement shown;
    static BuildGunPayloads.@Nullable View verdict;
    /** The client's own check of the hologram (the server's verdict wins where it has one). */
    static boolean localOk;
    static Component localMessage = Component.empty();

    private static BuildGunPayloads.@Nullable Select sent;
    private static boolean wasHolding;
    /** The hotbar slot held before the gun, to go back to when the gun is put away. */
    private static int before = -1, lastSlot = -1;
    private static int ticks, lastColor, lastRebuild, outlinedSelection;
    private static List<BlockPos> shape = List.of(), blocked = List.of();

    private BuildGunClient() {
    }

    /**
     * The keys, all under SIFTEC Build Gun in Controls: Q build menu; 1 to 0 build hotbar slots (Alt + scroll switches
     * among the ten bars); left click build, and held in dismantle mode take down; right click cancel, or with nothing
     * picked put the gun away (scroll with nothing picked does too); scroll rotate; Alt + R mirror; middle click picks the
     * building aimed at; H lock the hologram, then the arrow keys nudge it a block on X and Z and Page Up/Down up and
     * down; F dismantle mode, where Ctrl selects up to 50 buildings and G keeps only those of the type aimed at.
     */
    public static void register() {
        KeyMappingHelper.registerKeyMapping(MENU);
        for (KeyMapping k : SLOTS) KeyMappingHelper.registerKeyMapping(k);
        for (KeyMapping k : List.of(PLACE, CANCEL, SAMPLE, ALT, MIRROR, LOCK, NUDGE_FORWARD, NUDGE_BACK, NUDGE_LEFT, NUDGE_RIGHT,
            NUDGE_UP, NUDGE_DOWN, DISMANTLE, MULTI, FILTER)) KeyMappingHelper.registerKeyMapping(k);
        ClientTickEvents.START_CLIENT_TICK.register(BuildGunClient::keys);
        ClientTickEvents.END_CLIENT_TICK.register(BuildGunClient::hologram);
        ClientPlayNetworking.registerGlobalReceiver(BuildGunPayloads.View.TYPE, (payload, context) -> Minecraft.getInstance().execute(() -> verdict = payload));
        ClientPlayNetworking.registerGlobalReceiver(BuildGunPayloads.Hotbars.TYPE, (payload, context) -> Minecraft.getInstance().execute(() -> {
            for (int i = 0; i < bars.size(); i++) bars.set(i, i < payload.slots().size() ? payload.slots().get(i) : "");
            page = Math.floorMod(payload.page(), BuildHotbars.BARS);
        }));
        ClientPlayNetworking.registerGlobalReceiver(BuildGunPayloads.GunState.TYPE, (payload, context) -> Minecraft.getInstance().execute(() -> {
            unlocked = new HashSet<>(payload.unlocked());
            carried = new HashMap<>(payload.carried());
        }));
        BuildGunHud.register();
    }

    static boolean holding(Minecraft mc) {
        return mc.player != null && mc.level != null && !mc.player.isSpectator() && mc.player.getMainHandItem().getItem() instanceof BuildGunItem;
    }

    /** True while the gun is in hand with no screen open: the build hotbar and the HUD show. */
    public static boolean active() {
        Minecraft mc = Minecraft.getInstance();
        return holding(mc) && mc.gui.screen() == null;
    }

    /** The picked building, or null. */
    static @Nullable BuildingDef current() {
        return picked.isEmpty() || dismantle ? null : Buildings.def(picked);
    }

    /** True if the player may pick the building: their company has unlocked it (creative players have them all). */
    static boolean available(Minecraft mc, String id) {
        BuildingDef def = Buildings.def(id);
        if (def == null) return false;
        if (mc.player != null && mc.player.hasInfiniteMaterials()) return true;
        return unlocked.contains(id);
    }

    /** Picks a building for the hologram (from the menu, the hotbar or the eyedropper): build mode, a fresh hologram. */
    public static void pick(String id) {
        picked = id;
        dismantle = false;
        locked = false;
        frozen = null;
        clearSelection();
    }

    /** The building in a slot of the bar shown. */
    static String inSlot(int i) {
        return bars.get(page * BuildHotbars.SLOTS + i);
    }

    /** Binds a building to a slot of the bar shown (the build menu's 1 to 0), here and on the server. */
    static void bind(int i, String id) {
        bars.set(page * BuildHotbars.SLOTS + i, id);
        if (ClientPlayNetworking.canSend(BuildGunPayloads.Bind.TYPE)) ClientPlayNetworking.send(new BuildGunPayloads.Bind(page, i, id));
    }

    // ------------------------------------------------------------------------------------------------ scroll

    /** The mouse wheel, from the mouse handler: true if the gun used it (the vanilla hotbar must not move then). */
    public static boolean scroll(double amount) {
        Minecraft mc = Minecraft.getInstance();
        if (amount == 0 || !active()) return false;
        int step = amount > 0 ? -1 : 1;
        if (ALT.isDown()) {
            // Alt + scroll: the next or previous of the ten build hotbars
            page = Math.floorMod(page + step, BuildHotbars.BARS);
            slot = -1;
            if (ClientPlayNetworking.canSend(BuildGunPayloads.Page.TYPE)) ClientPlayNetworking.send(new BuildGunPayloads.Page(page));
            return true;
        }
        if (dismantle) return true;
        if (current() != null) {
            // a quarter turn a notch; turned back by three quarters
            turns = (turns + (step > 0 ? 1 : 3)) & 3;
            if (frozen != null) frozen = step > 0 ? frozen.turned() : frozen.turned().turned().turned();
            return true;
        }
        // nothing picked: back to the vanilla hotbar and what was held before the gun
        putAway(mc);
        return true;
    }

    // ------------------------------------------------------------------------------------------------ keys

    private static final List<KeyMapping> OWN = new ArrayList<>();

    private static List<KeyMapping> own() {
        if (OWN.isEmpty()) {
            OWN.addAll(List.of(MENU, PLACE, CANCEL, SAMPLE, MIRROR, LOCK, NUDGE_FORWARD, NUDGE_BACK, NUDGE_LEFT, NUDGE_RIGHT, NUDGE_UP,
                NUDGE_DOWN, DISMANTLE, FILTER));
            OWN.addAll(List.of(SLOTS));
        }
        return OWN;
    }

    private static void keys(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) return;
        Inventory inventory = player.getInventory();
        boolean holding = holding(mc);
        if (!holding) {
            if (wasHolding) clear();
            wasHolding = false;
            // presses made without the gun belong to the game, not to the gun later
            drain(own());
            before = inventory.getSelectedSlot();
            lastSlot = before;
            return;
        }
        if (!wasHolding) {
            drain(own());
            wasHolding = true;
            if (before < 0 || before == inventory.getSelectedSlot()) before = lastSlot;
            held = 0;
        }
        lastSlot = inventory.getSelectedSlot();
        Options o = mc.options;
        // the game's own uses of these keys are the gun's while it is in hand: no dropping it (Q), no swapping hands
        // (F), no picking blocks (middle click), no hotbar keys, no hitting or using
        drain(o.keyDrop, o.keySwapOffhand, o.keyPickItem, o.keyAttack, o.keyUse);
        drain(o.keyHotbarSlots);
        o.keyAttack.setDown(false);
        o.keyUse.setDown(false);
        if (mc.gui.screen() != null) {
            drain(own());
            held = 0;
            return;
        }
        while (MENU.consumeClick()) {
            mc.gui.setScreen(new BuildMenuScreen());
            drain(own());
            return;
        }
        for (int i = 0; i < SLOTS.length; i++) {
            while (SLOTS[i].consumeClick()) pickSlot(mc, i);
        }
        while (DISMANTLE.consumeClick()) {
            dismantle = !dismantle;
            locked = false;
            frozen = null;
            held = 0;
            clearSelection();
        }
        if (dismantle) dismantleKeys(mc);
        else buildKeys(mc, player);
    }

    private static void pickSlot(Minecraft mc, int i) {
        slot = i;
        String id = inSlot(i);
        if (id.isEmpty()) {
            picked = "";
            locked = false;
            frozen = null;
            return;
        }
        if (!available(mc, id)) {
            BuildingDef def = Buildings.def(id);
            if (mc.player != null && def != null) mc.player.sendOverlayMessage(Component.translatable("siftec.build_gun.locked", Buildings.unlockName(def)));
            return;
        }
        pick(id);
    }

    private static void buildKeys(Minecraft mc, LocalPlayer player) {
        drain(MULTI, FILTER);
        held = 0;
        while (PLACE.consumeClick()) {
            BuildingDef def = current();
            Placement spot = shown;
            if (def == null || spot == null) {
                if (def == null) player.sendOverlayMessage(Component.translatable("siftec.build_gun.pick"));
                continue;
            }
            if (ClientPlayNetworking.canSend(BuildGunPayloads.Place.TYPE)) {
                ClientPlayNetworking.send(new BuildGunPayloads.Place(def.id(), spot.anchor(), spot.facing().get2DDataValue(), spot.flip()));
            }
            // a locked hologram is let go once it is built, as in Satisfactory
            locked = false;
            frozen = null;
        }
        while (CANCEL.consumeClick()) {
            if (!picked.isEmpty()) {
                picked = "";
                slot = -1;
                locked = false;
                frozen = null;
            } else {
                putAway(mc);
                return;
            }
        }
        while (SAMPLE.consumeClick()) {
            BuildGunPayloads.View v = verdict;
            if (v != null && !v.target().isEmpty() && available(mc, v.target())) pick(v.target());
        }
        while (MIRROR.consumeClick()) {
            if (!ALT.isDown()) continue;
            flip = !flip;
            if (frozen != null) frozen = frozen.flipped();
        }
        while (LOCK.consumeClick()) {
            locked = !locked && shown != null;
            frozen = locked ? shown : null;
        }
        // the arrow keys nudge a locked hologram a block on X and Z, relative to where the player looks; Page Up and
        // Page Down a block up and down
        Direction ahead = player.getDirection();
        nudge(NUDGE_FORWARD, ahead.getStepX(), 0, ahead.getStepZ());
        nudge(NUDGE_BACK, -ahead.getStepX(), 0, -ahead.getStepZ());
        nudge(NUDGE_LEFT, ahead.getCounterClockWise().getStepX(), 0, ahead.getCounterClockWise().getStepZ());
        nudge(NUDGE_RIGHT, ahead.getClockWise().getStepX(), 0, ahead.getClockWise().getStepZ());
        nudge(NUDGE_UP, 0, 1, 0);
        nudge(NUDGE_DOWN, 0, -1, 0);
    }

    private static void nudge(KeyMapping key, int dx, int dy, int dz) {
        while (key.consumeClick()) {
            if (locked && frozen != null) frozen = frozen.moved(dx, dy, dz);
        }
    }

    private static void dismantleKeys(Minecraft mc) {
        drain(SAMPLE, MIRROR, LOCK, NUDGE_FORWARD, NUDGE_BACK, NUDGE_LEFT, NUDGE_RIGHT, NUDGE_UP, NUDGE_DOWN);
        BuildGunPayloads.View v = verdict;
        boolean aimed = v != null && v.hasBox() && !v.target().isEmpty();
        // Ctrl held: whatever is aimed at joins the selection (only its type with the filter on), up to 50
        if (MULTI.isDown() && aimed && v.ok() && !vehicle(v.target())) select(v);
        while (FILTER.consumeClick()) {
            if (!filter.isEmpty()) {
                filter = "";
            } else if (aimed) {
                filter = v.target();
                selection.removeIf(p -> !p.id().equals(filter));
            }
        }
        while (CANCEL.consumeClick()) {
            if (!selection.isEmpty()) {
                clearSelection();
            } else {
                dismantle = false;
                filter = "";
            }
        }
        // left click held for a second takes it all down
        PLACE.consumeClick();
        drain(PLACE);
        if (!PLACE.isDown()) {
            held = 0;
            return;
        }
        if (held < 0) return;
        boolean something = !selection.isEmpty() || aimed && v.ok();
        held = something ? held + 1 : 0;
        if (held < HOLD_TICKS) return;
        held = -1;
        List<BlockPos> targets = new ArrayList<>();
        boolean vehicle = false;
        if (!selection.isEmpty()) {
            for (Picked p : selection) targets.add(p.at());
        } else if (aimed) {
            vehicle = vehicle(v.target()) && !v.at().equals(BlockPos.ZERO) && Vehicles.aimed(mc.player) != null;
            if (!vehicle) targets.add(v.at());
        }
        if (ClientPlayNetworking.canSend(BuildGunPayloads.Dismantle.TYPE)) ClientPlayNetworking.send(new BuildGunPayloads.Dismantle(targets, vehicle));
        clearSelection();
    }

    private static boolean vehicle(String id) {
        BuildingDef def = Buildings.def(id);
        return def != null && def.vehicle();
    }

    /** Adds the building in the verdict to the mass selection, if it is not in it and there is room. */
    static void select(BuildGunPayloads.View v) {
        if (!filter.isEmpty() && !filter.equals(v.target())) return;
        if (selection.size() >= BuildGunPayloads.MAX_DISMANTLE) return;
        for (Picked p : selection) if (p.at().equals(v.at())) return;
        selection.add(new Picked(v.at(), v.target(), new AABB(v.min().getX(), v.min().getY(), v.min().getZ(), v.max().getX() + 1, v.max().getY() + 1, v.max().getZ() + 1)));
    }

    static void clearSelection() {
        selection.clear();
    }

    /** Back to the vanilla hotbar and the item held before the gun (another slot if that was the gun's own). */
    private static void putAway(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) return;
        Inventory inventory = player.getInventory();
        int gun = inventory.getSelectedSlot(), to = before;
        if (to < 0 || to >= Inventory.getSelectionSize() || to == gun) to = (gun + 1) % Inventory.getSelectionSize();
        inventory.setSelectedSlot(to);
        picked = "";
        slot = -1;
        dismantle = false;
    }

    private static void drain(KeyMapping... keys) {
        for (KeyMapping k : keys) while (k.consumeClick()) ;
    }

    private static void drain(List<KeyMapping> keys) {
        for (KeyMapping k : keys) while (k.consumeClick()) ;
    }

    // ------------------------------------------------------------------------------------------------ hologram

    private static void hologram(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (!holding(mc)) {
            if (shown != null || sent != null) clear();
            return;
        }
        ticks++;
        BuildingDef def = current();
        if (def != null && !available(mc, def.id()) && !unlocked.isEmpty()) {
            // locked since it was picked (another company's unlock went away): put it down
            picked = "";
            def = null;
        }
        Placement spot = null;
        if (!dismantle && def != null) {
            spot = locked && frozen != null && frozen.def() == def ? frozen : BuildGun.aim(player, def, turns, flip && !def.structure());
        }
        send(def, spot);
        // a miner: the server snapped the spot sent onto the node near it (the client does not know the nodes), so the
        // hologram shows where it will really go
        BuildGunPayloads.View snap = verdict;
        if (spot != null && snap != null && snap.snapped() && snap.from().equals(spot.anchor()) && spot.def().node()) {
            spot = spot.moved(snap.min().getX() - spot.anchor().getX(), snap.min().getY() - spot.anchor().getY(), snap.min().getZ() - spot.anchor().getZ());
        }
        Outliner outliner = Outliner.getInstance();
        outlineSelection(outliner);
        if (dismantle) {
            outliner.remove(SHAPE);
            outliner.remove(BLOCKED);
            shown = null;
            BuildGunPayloads.View v = verdict;
            int color = v != null && v.ok() ? DISMANTLE_COLOR : REFUSED;
            // a vehicle: the outline is its turned body, along its whole length
            VehicleEntity vehicle = v != null && v.hasBox() ? Vehicles.aimed(player) : null;
            if (vehicle != null) {
                outliner.remove(TARGET);
                Vec3[] c = vehicle.bodyCorners(VEHICLE_OUTLINE_GROW);
                for (int i = 0; i < EDGES.length; i++) {
                    outliner.showLine(VEHICLE_EDGE[i], c[EDGES[i][0]], c[EDGES[i][1]]).colored(color).disableLineNormals().lineWidth(PrefabPreview.HIGHLIGHT_LINE);
                }
            } else if (v != null && v.hasBox()) {
                removeVehicleEdges(outliner);
                AABB box = new AABB(v.min().getX(), v.min().getY(), v.min().getZ(), v.max().getX() + 1, v.max().getY() + 1, v.max().getZ() + 1);
                outliner.showAABB(TARGET, box).colored(color).disableLineNormals().lineWidth(PrefabPreview.HIGHLIGHT_LINE).withFaceTexture(AllSpecialTextures.HIGHLIGHT_CHECKERED);
            } else {
                removeVehicleEdges(outliner);
                outliner.remove(TARGET);
            }
            return;
        }
        outliner.remove(TARGET);
        removeVehicleEdges(outliner);
        if (spot == null) {
            outliner.remove(SHAPE);
            outliner.remove(BLOCKED);
            shown = null;
            if (def != null) {
                localOk = false;
                localMessage = Component.translatable("siftec.prefab.aim", Buildings.RANGE);
            }
            return;
        }
        // the HUB is a big structure: its ground and space are checked at most every third tick, and twice a second
        boolean rebuild = def.structure() ? (!spot.equals(shown) && ticks - lastRebuild >= PrefabPreview.MIN_RECHECK_TICKS) || ticks - lastRebuild >= PrefabPreview.RECHECK_TICKS
            : !spot.equals(shown) || ticks % PrefabPreview.RECHECK_TICKS == 0;
        if (rebuild) {
            lastRebuild = ticks;
            VehicleKind vehicle = Vehicles.kind(def);
            if (vehicle != null) {
                // a vehicle: its footprint, and whether there is room for it (unlock, land and cost are the server's)
                Buildings.Check check = Vehicles.check(mc.level, spot, null, null, player.hasInfiniteMaterials());
                shape = Vehicles.cells(spot, vehicle);
                blocked = new ArrayList<>();
                localOk = check.ok();
                localMessage = check.ok() ? Component.translatable("siftec.build_gun.fits_vehicle", def.name()) : check.message();
            } else if (def.structure()) {
                Prefabs.Check check = Prefabs.check(mc.level, BuildGun.hubSpot(spot), null, false);
                shape = hubCells(spot);
                blocked = new ArrayList<>(check.blocked);
                localOk = check.ok();
                localMessage = check.ok() ? Component.translatable(def.hub() ? "siftec.build_gun.fits_hub" : "siftec.build_gun.fits_gateway") : check.message();
            } else {
                // the cost is the server's to check: it is paid from the backpack too, which the client does not see
                Buildings.Check check = Buildings.check(mc.level, spot, null, null, player.hasInfiniteMaterials());
                shape = cells(spot);
                blocked = new ArrayList<>(check.blocked);
                localOk = check.ok();
                localMessage = check.message();
            }
        }
        boolean ok = fits(def, spot);
        int color = ok ? BLUE : RED;
        if (rebuild || color != lastColor) {
            outliner.showCluster(SHAPE, shape).colored(color).disableLineNormals().lineWidth(PrefabPreview.SHAPE_LINE).withFaceTexture(AllSpecialTextures.CHECKERED);
            if (blocked.isEmpty()) {
                outliner.remove(BLOCKED);
            } else {
                outliner.showCluster(BLOCKED, blocked).colored(RED).disableLineNormals().lineWidth(PrefabPreview.HIGHLIGHT_LINE).withFaceTexture(AllSpecialTextures.HIGHLIGHT_CHECKERED);
            }
            lastColor = color;
        } else {
            outliner.keep(SHAPE);
            outliner.keep(BLOCKED);
        }
        shown = spot;
    }

    /** True if the server's verdict on this spot (or, until it comes, the client's own check) says the building fits. */
    static boolean fits(BuildingDef def, Placement spot) {
        BuildGunPayloads.View v = verdict;
        boolean same = v != null && v.min().equals(spot.anchor());
        // whether a miner stands over an ore node only the server knows: its verdict is the one that counts
        boolean oreNode = def.node() && def.fluidNode().isEmpty();
        return oreNode ? same && v.ok() : localOk && (!same || v.ok());
    }

    /** What the HUD says about the hologram: the server's verdict where it found a problem the client cannot see. */
    static Component status() {
        BuildingDef def = current();
        Placement spot = shown;
        BuildGunPayloads.View v = verdict;
        if (def == null || spot == null) return localMessage;
        boolean same = v != null && v.min().equals(spot.anchor());
        boolean oreNode = def.node() && def.fluidNode().isEmpty();
        return same && (oreNode || localOk && !v.ok()) ? v.message() : localMessage;
    }

    private static void outlineSelection(Outliner outliner) {
        int n = dismantle ? selection.size() : 0;
        for (int i = 0; i < n; i++) {
            outliner.showAABB(SELECTED_BOX[i], selection.get(i).box()).colored(DISMANTLE_COLOR).disableLineNormals().lineWidth(PrefabPreview.HIGHLIGHT_LINE)
                .withFaceTexture(AllSpecialTextures.HIGHLIGHT_CHECKERED);
        }
        for (int i = n; i < outlinedSelection; i++) outliner.remove(SELECTED_BOX[i]);
        outlinedSelection = n;
    }

    private static void removeVehicleEdges(Outliner outliner) {
        for (Object key : VEHICLE_EDGE) outliner.remove(key);
    }

    private static List<BlockPos> cells(Placement p) {
        BuildingTemplate t = BuildingTemplate.of(p.def());
        List<BlockPos> out = new ArrayList<>();
        if (t == null) return out;
        for (BuildingTemplate.Cell cell : t.members) out.add(p.at(cell));
        return out;
    }

    /** Every block the HUB will ever have, in the world. */
    private static List<BlockPos> hubCells(Placement p) {
        Prefabs.Spot spot = BuildGun.hubSpot(p);
        Template t = spot.kind().template();
        List<BlockPos> out = new ArrayList<>();
        for (long key : t.keys()) {
            if (!t.solidAtAnyLevel(key)) continue;
            BlockPos local = BlockPos.of(key);
            out.add(spot.at(local.getX(), local.getY(), local.getZ()));
        }
        return out;
    }

    /** Tells the server what is picked and where the hologram stands, when that changes. */
    private static void send(@Nullable BuildingDef def, @Nullable Placement spot) {
        BuildGunPayloads.Select now = new BuildGunPayloads.Select(def == null ? "" : def.id(), dismantle, spot != null,
            spot == null ? BlockPos.ZERO : spot.anchor(), spot == null ? 0 : spot.facing().get2DDataValue(), spot != null && spot.flip());
        if (now.equals(sent) || !ClientPlayNetworking.canSend(BuildGunPayloads.Select.TYPE)) return;
        sent = now;
        ClientPlayNetworking.send(now);
    }

    private static void clear() {
        Outliner outliner = Outliner.getInstance();
        outliner.remove(SHAPE);
        outliner.remove(BLOCKED);
        outliner.remove(TARGET);
        removeVehicleEdges(outliner);
        for (int i = 0; i < outlinedSelection; i++) outliner.remove(SELECTED_BOX[i]);
        outlinedSelection = 0;
        shown = null;
        frozen = null;
        locked = false;
        dismantle = false;
        held = 0;
        clearSelection();
        filter = "";
        verdict = null;
        if (sent != null && (sent.hasSpot() || sent.dismantle()) && ClientPlayNetworking.canSend(BuildGunPayloads.Select.TYPE)) {
            sent = new BuildGunPayloads.Select(sent.building(), false, false, BlockPos.ZERO, 0, false);
            ClientPlayNetworking.send(sent);
        }
    }

    /** Forgets everything on leaving a world. */
    public static void reset() {
        picked = "";
        filter = "";
        dismantle = locked = flip = wasHolding = false;
        turns = 0;
        page = 0;
        slot = -1;
        before = lastSlot = -1;
        held = 0;
        Collections.fill(bars, "");
        unlocked = new HashSet<>();
        carried = new HashMap<>();
        selection.clear();
        frozen = shown = null;
        sent = null;
        verdict = null;
    }
}
