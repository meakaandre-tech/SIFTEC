package com.meakaandre.siftec.client;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildGun;
import com.meakaandre.siftec.building.BuildGunItem;
import com.meakaandre.siftec.building.BuildGunPayloads;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.BuildingTemplate;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.Category;
import com.meakaandre.siftec.building.Placement;
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
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The Build Gun on the client: its keys, the hologram and the line of text above the hotbar.
 * <ul>
 * <li>Tab: next tab (Production, Power, Logistics, Foundations, Special)</li>
 * <li>Ctrl + Scroll: next or previous building in the tab</li>
 * <li>R: turn a quarter; F: mirror left to right</li>
 * <li>Ctrl on its own (press and let go): nudge mode, the hologram stays put and W, A, S, D move it a block</li>
 * <li>X: dismantle mode</li>
 * <li>Left click: cancel (leave nudge mode, then dismantle mode, then put the building away)</li>
 * <li>Right click: build (or dismantle) - sent to the server as an ordinary use of the item</li>
 * </ul>
 * All keys can be changed in the controls screen, under SIFTEC Build Gun.
 */
public final class BuildGunClient {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Siftec.id("build_gun"));
    public static final KeyMapping NEXT_TAB = new KeyMapping("key.siftec.build_gun.tab", InputConstants.KEY_TAB, CATEGORY);
    public static final KeyMapping ROTATE = new KeyMapping("key.siftec.build_gun.rotate", InputConstants.KEY_R, CATEGORY);
    public static final KeyMapping FLIP = new KeyMapping("key.siftec.build_gun.flip", InputConstants.KEY_F, CATEGORY);
    public static final KeyMapping NUDGE = new KeyMapping("key.siftec.build_gun.nudge", InputConstants.KEY_LCONTROL, CATEGORY);
    public static final KeyMapping DISMANTLE = new KeyMapping("key.siftec.build_gun.dismantle", InputConstants.KEY_X, CATEGORY);

    private static final Object SHAPE = "siftec:build_gun_shape", BLOCKED = "siftec:build_gun_blocked", TARGET = "siftec:build_gun_target";
    private static final int GREEN = 0x5CD65C, RED = 0xE03C3C, BLUE = 0x4AA8FF;

    private static int category;
    private static final int[] picked = new int[Category.values().length];
    private static boolean selected, dismantle, nudge, flip, ctrlWasDown, scrolled, wasHolding;
    private static int turns;
    private static @Nullable Placement frozen, shown;
    private static BuildGunPayloads.@Nullable Select sent;
    private static BuildGunPayloads.@Nullable View verdict;
    private static int ticks, lastColor;
    private static List<BlockPos> shape = List.of(), blocked = List.of();
    private static Component localMessage = Component.empty();
    private static boolean localOk;

    private BuildGunClient() {
    }

    public static void register() {
        KeyMappingHelper.registerKeyMapping(NEXT_TAB);
        KeyMappingHelper.registerKeyMapping(ROTATE);
        KeyMappingHelper.registerKeyMapping(FLIP);
        KeyMappingHelper.registerKeyMapping(NUDGE);
        KeyMappingHelper.registerKeyMapping(DISMANTLE);
        ClientTickEvents.START_CLIENT_TICK.register(BuildGunClient::keys);
        ClientTickEvents.END_CLIENT_TICK.register(BuildGunClient::hologram);
        ClientPlayNetworking.registerGlobalReceiver(BuildGunPayloads.View.TYPE, (payload, context) -> Minecraft.getInstance().execute(() -> verdict = payload));
    }

    private static boolean holding(Minecraft mc) {
        return mc.player != null && mc.level != null && !mc.player.isSpectator() && mc.player.getMainHandItem().getItem() instanceof BuildGunItem;
    }

    public static boolean nudging() {
        Minecraft mc = Minecraft.getInstance();
        return nudge && mc.gui.screen() == null && holding(mc);
    }

    private static List<BuildingDef> list(Minecraft mc) {
        return Buildings.of(Category.values()[category], mc.player != null && mc.player.hasInfiniteMaterials());
    }

    private static @Nullable BuildingDef current(Minecraft mc) {
        if (!selected) return null;
        List<BuildingDef> defs = list(mc);
        if (defs.isEmpty()) return null;
        int i = Math.floorMod(picked[category], defs.size());
        return defs.get(i);
    }

    /** Ctrl + Scroll, from the mouse handler: true if the gun used the scroll (the hotbar must not move then). */
    public static boolean scroll(double amount) {
        Minecraft mc = Minecraft.getInstance();
        if (amount == 0 || mc.gui.screen() != null || !holding(mc) || !NUDGE.isDown()) return false;
        scrolled = true;
        dismantle = false;
        if (selected) picked[category] += amount > 0 ? -1 : 1;
        selected = true;
        frozen = null;
        nudge = false;
        return true;
    }

    // ------------------------------------------------------------------------------------------------ keys

    private static void keys(Minecraft mc) {
        boolean holding = holding(mc) && mc.gui.screen() == null;
        Options o = mc.options;
        if (!holding) {
            if (wasHolding) clear();
            wasHolding = false;
            return;
        }
        if (!wasHolding) {
            // movement presses from before the gun was in hand must not nudge anything
            drain(o.keyUp, o.keyDown, o.keyLeft, o.keyRight, NEXT_TAB, ROTATE, FLIP, DISMANTLE);
            wasHolding = true;
        }
        while (NEXT_TAB.consumeClick()) {
            category = (category + 1) % Category.values().length;
            dismantle = false;
            nudge = false;
            frozen = null;
            selected = true;
        }
        while (ROTATE.consumeClick()) {
            turns = (turns + 1) & 3;
            if (frozen != null) frozen = frozen.turned();
        }
        // F is also the game's swap-hands key: with the gun in hand it only flips
        drain(o.keySwapOffhand);
        while (FLIP.consumeClick()) flip();
        while (DISMANTLE.consumeClick()) {
            dismantle = !dismantle;
            nudge = false;
            frozen = null;
        }
        boolean ctrl = NUDGE.isDown();
        if (ctrl && !ctrlWasDown) scrolled = false;
        if (!ctrl && ctrlWasDown && !scrolled && !dismantle) {
            nudge = !nudge;
            frozen = nudge ? shown : null;
            if (frozen == null) nudge = false;
        }
        ctrlWasDown = ctrl;
        // W, A, S, D: in nudge mode a press moves the hologram a block, relative to where the player looks
        Direction ahead = mc.player.getDirection();
        int[][] moves = {{ahead.getStepX(), ahead.getStepZ()}, {-ahead.getStepX(), -ahead.getStepZ()},
            {ahead.getCounterClockWise().getStepX(), ahead.getCounterClockWise().getStepZ()}, {ahead.getClockWise().getStepX(), ahead.getClockWise().getStepZ()}};
        KeyMapping[] wasd = {o.keyUp, o.keyDown, o.keyLeft, o.keyRight};
        for (int i = 0; i < 4; i++) {
            while (wasd[i].consumeClick()) {
                if (nudge && frozen != null) frozen = frozen.moved(moves[i][0], 0, moves[i][1]);
            }
        }
        // left click cancels, and never mines with the gun
        while (o.keyAttack.consumeClick()) cancel();
    }

    private static void flip() {
        flip = !flip;
        if (frozen != null) frozen = frozen.flipped();
    }

    private static void cancel() {
        if (nudge) {
            nudge = false;
            frozen = null;
        } else if (dismantle) {
            dismantle = false;
        } else {
            selected = false;
        }
    }

    private static void drain(KeyMapping... keys) {
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
        BuildingDef def = current(mc);
        Placement spot = null;
        if (!dismantle && def != null) {
            spot = nudge && frozen != null && frozen.def() == def ? frozen : BuildGun.aim(player, def, turns, flip);
        }
        send(def, spot);
        Outliner outliner = Outliner.getInstance();
        if (dismantle) {
            outliner.remove(SHAPE);
            outliner.remove(BLOCKED);
            shown = null;
            BuildGunPayloads.View v = verdict;
            if (v != null && v.hasBox()) {
                AABB box = new AABB(v.min().getX(), v.min().getY(), v.min().getZ(), v.max().getX() + 1, v.max().getY() + 1, v.max().getZ() + 1);
                outliner.showAABB(TARGET, box).colored(v.ok() ? RED : BLUE).disableLineNormals().lineWidth(1 / 12f).withFaceTexture(AllSpecialTextures.HIGHLIGHT_CHECKERED);
            } else {
                outliner.remove(TARGET);
            }
            if (ticks % 10 == 0) player.sendOverlayMessage(Component.translatable("siftec.build_gun.overlay_dismantle",
                v == null ? Component.translatable("siftec.build_gun.dismantle_aim") : v.message()));
            return;
        }
        outliner.remove(TARGET);
        if (spot == null) {
            outliner.remove(SHAPE);
            outliner.remove(BLOCKED);
            shown = null;
            if (ticks % 10 == 0) player.sendOverlayMessage(def == null ? Component.translatable("siftec.build_gun.overlay_none", tab())
                : Component.translatable("siftec.build_gun.overlay", tab(), def.name(), Component.translatable("siftec.prefab.aim", Buildings.RANGE)));
            return;
        }
        boolean rebuild = !spot.equals(shown) || ticks % 10 == 0;
        if (rebuild) {
            Buildings.Check check = Buildings.check(mc.level, spot, null, player.getInventory(), player.hasInfiniteMaterials());
            shape = cells(spot);
            blocked = new ArrayList<>(check.blocked);
            localOk = check.ok();
            localMessage = check.message();
        }
        BuildGunPayloads.View v = verdict;
        boolean same = v != null && v.min().equals(spot.anchor());
        boolean ok = localOk && (!same || v.ok());
        int color = ok ? GREEN : RED;
        if (rebuild || color != lastColor) {
            outliner.showCluster(SHAPE, shape).colored(color).disableLineNormals().lineWidth(1 / 16f).withFaceTexture(AllSpecialTextures.CHECKERED);
            if (blocked.isEmpty()) {
                outliner.remove(BLOCKED);
            } else {
                outliner.showCluster(BLOCKED, blocked).colored(RED).disableLineNormals().lineWidth(1 / 12f).withFaceTexture(AllSpecialTextures.HIGHLIGHT_CHECKERED);
            }
            lastColor = color;
        } else {
            outliner.keep(SHAPE);
            outliner.keep(BLOCKED);
        }
        shown = spot;
        if (ticks % 10 == 0) {
            Component status = localOk && same && !v.ok() ? v.message() : localMessage;
            player.sendOverlayMessage(Component.translatable(nudge ? "siftec.build_gun.overlay_nudge" : "siftec.build_gun.overlay", tab(), def.name(), status));
        }
    }

    private static Component tab() {
        return Component.translatable(Category.values()[category].key());
    }

    private static List<BlockPos> cells(Placement p) {
        BuildingTemplate t = BuildingTemplate.of(p.def());
        List<BlockPos> out = new ArrayList<>();
        if (t == null) return out;
        for (BuildingTemplate.Cell cell : t.members) out.add(p.at(cell));
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
        shown = null;
        frozen = null;
        nudge = false;
        verdict = null;
        if (sent != null && (sent.hasSpot() || sent.dismantle()) && ClientPlayNetworking.canSend(BuildGunPayloads.Select.TYPE)) {
            sent = new BuildGunPayloads.Select(sent.building(), false, false, BlockPos.ZERO, 0, false);
            ClientPlayNetworking.send(sent);
        }
    }

    /** Forgets everything on leaving a world. */
    public static void reset() {
        category = 0;
        java.util.Arrays.fill(picked, 0);
        selected = dismantle = nudge = flip = ctrlWasDown = scrolled = wasHolding = false;
        turns = 0;
        frozen = shown = null;
        sent = null;
        verdict = null;
    }
}
