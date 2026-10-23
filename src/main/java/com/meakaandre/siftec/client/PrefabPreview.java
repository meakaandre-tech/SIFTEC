package com.meakaandre.siftec.client;

import com.meakaandre.siftec.prefab.PrefabItem;
import com.meakaandre.siftec.prefab.Prefabs;
import com.meakaandre.siftec.prefab.PreviewPayload;
import com.meakaandre.siftec.prefab.Template;
import com.zurrtum.create.client.AllSpecialTextures;
import com.zurrtum.create.client.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The Build Gun hologram: while the player holds a HUB or Wormhole Gateway, the whole structure is outlined where
 * it would go, green where it fits and red where it does not, with the blocks in the way marked. The ground and the
 * space are checked here; the land rules (claims, spawn area, one Gateway) come from the server's verdict on the
 * same spot.
 */
public final class PrefabPreview {
    private static final Object SHAPE = "siftec:prefab_shape", BLOCKED = "siftec:prefab_blocked";
    private static final int GREEN = 0x5CD65C, RED = 0xE03C3C;
    /** Outline widths: the shape's, and the thicker one for blocked blocks and targets (blocks). */
    static final float SHAPE_LINE = 1 / 16f, HIGHLIGHT_LINE = 1 / 12f;
    /** A big structure's ground is checked again at most this often when the spot moves, and at least this often (ticks). */
    static final int MIN_RECHECK_TICKS = 3, RECHECK_TICKS = 10;
    /** The action-bar message is sent again this often (ticks). */
    static final int OVERLAY_TICKS = 10;
    private static @Nullable PreviewPayload verdict;
    private static Prefabs.@Nullable Spot shown, checked;
    private static List<BlockPos> shape = List.of(), blocked = List.of();
    private static boolean fits;
    private static Component message = Component.empty();
    private static int ticks, lastColor, lastRebuild;

    private PrefabPreview() {
    }

    public static void accept(PreviewPayload payload) {
        verdict = payload;
    }

    public static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        Prefabs.Spot spot = null;
        if (player != null && mc.level != null && !player.isSpectator() && player.getMainHandItem().getItem() instanceof PrefabItem item) spot = Prefabs.aim(player, item.kind);
        if (spot == null) {
            if (shown != null) {
                Outliner.getInstance().remove(SHAPE);
                Outliner.getInstance().remove(BLOCKED);
                shown = null;
                checked = null;
            }
            return;
        }
        ticks++;
        boolean rebuild = false;
        // checking the ground under a big structure is not free: at most every third tick, and again twice a second
        if (checked == null || (!spot.equals(checked) && ticks - lastRebuild >= MIN_RECHECK_TICKS) || ticks - lastRebuild >= RECHECK_TICKS) {
            Prefabs.Check check = Prefabs.check(mc.level, spot, null, false);
            shape = cells(spot);
            blocked = new ArrayList<>(check.blocked);
            fits = check.ok();
            message = check.message();
            checked = spot;
            lastRebuild = ticks;
            rebuild = true;
        }
        PreviewPayload v = verdict;
        boolean same = v != null && v.kind() == spot.kind().ordinal() && v.anchor().equals(checked.anchor()) && v.facing() == checked.facing().get2DDataValue();
        boolean ok = fits && (!same || v.ok());
        int color = ok ? GREEN : RED;
        Outliner outliner = Outliner.getInstance();
        if (rebuild || color != lastColor) {
            outliner.showCluster(SHAPE, shape).colored(color).disableLineNormals().lineWidth(SHAPE_LINE).withFaceTexture(AllSpecialTextures.CHECKERED);
            if (blocked.isEmpty()) {
                outliner.remove(BLOCKED);
            } else {
                outliner.showCluster(BLOCKED, blocked).colored(RED).disableLineNormals().lineWidth(HIGHLIGHT_LINE).withFaceTexture(AllSpecialTextures.HIGHLIGHT_CHECKERED);
            }
            lastColor = color;
        } else {
            outliner.keep(SHAPE);
            outliner.keep(BLOCKED);
        }
        if (ticks % OVERLAY_TICKS == 0) player.sendOverlayMessage(fits && same && !v.ok() ? v.message() : message);
        shown = spot;
    }

    /** Every block the structure will ever have, in the world. */
    private static List<BlockPos> cells(Prefabs.Spot spot) {
        Template t = spot.kind().template();
        List<BlockPos> out = new ArrayList<>();
        for (long key : t.keys()) {
            if (!t.solidAtAnyLevel(key)) continue;
            BlockPos local = BlockPos.of(key);
            out.add(spot.at(local.getX(), local.getY(), local.getZ()));
        }
        return out;
    }
}
