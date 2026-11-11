package com.meakaandre.siftec.client;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.command.FramesTests;
import com.mojang.blaze3d.platform.NativeImage;
import com.zurrtum.create.client.flywheel.api.visualization.VisualizationManager;
import com.zurrtum.create.client.vanillin.visuals.ItemDisplayVisual;
import com.zurrtum.create.client.vanillin.visuals.ItemFrameVisual;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The item frame checks that only the client can make, driven by {@link FramesTests.Probe} from
 * {@code /siftec selftest frames client <check>}. Logs "SELFTEST frames-client PASS/FAIL ..." and then "... frames
 * checked"; {@code done} logs "... done: N passed, M failed".
 * <ul>
 * <li>{@code frames}: item frames and item displays on a white wall: the client has their items, their render states hold
 * them, Create Fly's Vanillin (as shipped) takes each frame and display, and the picture shows the items (an empty
 * frame and the bare wall to compare with).</li>
 * </ul>
 */
public final class FramesClientTests {
    private static final String P = "SELFTEST frames-client ";
    private static int passed, failed;
    /** Steps waiting for their tick. */
    private static final Deque<Runnable> QUEUE = new ArrayDeque<>();
    private static int wait;

    private FramesClientTests() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(FramesTests.Probe.TYPE, (payload, context) -> Minecraft.getInstance().execute(() -> probe(payload)));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (wait > 0) {
                wait--;
                return;
            }
            Runnable next = QUEUE.poll();
            if (next != null) next.run();
        });
    }

    private static void log(String text) {
        Siftec.LOGGER.info(P + text);
    }

    private static void expect(String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        log((ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    private static void probe(FramesTests.Probe probe) {
        try {
            switch (probe.action()) {
                case "frames" -> frames();
                case "done" -> log("done: " + passed + " passed, " + failed + " failed");
                default -> log("no check " + probe.action());
            }
        } catch (RuntimeException e) {
            Siftec.LOGGER.error(P + probe.action() + " threw", e);
            expect(probe.action() + " runs to the end", false, e.toString());
            log(probe.action() + " checked");
        }
    }

    // ------------------------------------------------------------------------------------------------ steps and pictures

    /** Waits so many ticks, then runs the step (on the client thread, from its tick). */
    private static void then(int ticksFirst, Runnable step) {
        QUEUE.add(() -> {
            wait = ticksFirst;
            QUEUE.addFirst(step);
        });
    }

    /** The camera (the player, in spectator) at an eye position, looking yaw/pitch. */
    private static void look(double x, double eyeY, double z, float yaw, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.snapTo(x, eyeY - mc.player.getEyeHeight(), z, yaw, pitch);
        mc.player.setDeltaMovement(0, 0, 0);
        mc.player.setYHeadRot(yaw);
    }

    /** Hides the HUD (and the hand), takes the frame as drawn, and gives the picture to {@code then} before showing the HUD again. */
    private static void picture(Consumer<NativeImage> then) {
        Minecraft mc = Minecraft.getInstance();
        boolean hidden = mc.gui.hud.isHidden();
        if (!hidden) mc.gui.hud.toggle();
        // two frames with the HUD hidden, then the picture; the next step waits for it
        QUEUE.addFirst(() -> {
            wait = 2;
            QUEUE.addFirst(() -> {
                boolean[] got = {false};
                Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
                    try (image) {
                        then.accept(image);
                    } catch (RuntimeException e) {
                        Siftec.LOGGER.error(P + "a picture check threw", e);
                        expect("a picture check runs to the end", false, e.toString());
                    } finally {
                        got[0] = true;
                    }
                });
                // the readback arrives on a later frame: wait for it before going on
                QUEUE.addFirst(new Runnable() {
                    int tries;

                    @Override
                    public void run() {
                        if (!got[0] && tries++ < 100) {
                            QUEUE.addFirst(this);
                            return;
                        }
                        if (!got[0]) expect("the picture arrives", false, "no readback after 100 ticks");
                        if (!hidden && mc.gui.hud.isHidden()) mc.gui.hud.toggle();
                    }
                });
            });
        });
    }

    private static int r(int argb) {
        return argb >> 16 & 255;
    }

    private static int g(int argb) {
        return argb >> 8 & 255;
    }

    private static int b(int argb) {
        return argb & 255;
    }

    private static boolean differs(int a, int c, int tol) {
        return Math.abs(r(a) - r(c)) > tol || Math.abs(g(a) - g(c)) > tol || Math.abs(b(a) - b(c)) > tol;
    }

    // ------------------------------------------------------------------------------------------------ frames

    /**
     * The frames site: what the client holds and extracts for each item frame and item display (and a loose diamond),
     * then a picture straight at each, compared with the empty frame or the bare wall.
     */
    private static void frames() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        BlockPos w = FramesTests.FRAMES;
        double eyeY = w.getY() + FramesTests.FRAME_HEIGHT + 0.5, eyeZ = w.getZ() + 1 + 2.5;
        String[] names = FramesTests.FRAME_TARGETS;
        // what the client has
        for (int i = 0; i < names.length; i++) {
            double x = w.getX() + FramesTests.FRAME_STEP * i + 0.5;
            AABB box = new AABB(x - 0.6, eyeY - 1.2, w.getZ(), x + 0.6, eyeY + 1, w.getZ() + 2.2);
            Entity e = null;
            for (Entity f : mc.level.getEntities((Entity) null, box, f -> f instanceof ItemFrame || f instanceof Display.ItemDisplay || f instanceof ItemEntity)) e = f;
            ItemStack synced = e instanceof ItemFrame f ? f.getItem() : e instanceof Display.ItemDisplay d ? (d.itemRenderState() == null ? ItemStack.EMPTY : d.itemRenderState().itemStack())
                : e instanceof ItemEntity it ? it.getItem() : ItemStack.EMPTY;
            boolean wantItem = !names[i].endsWith("empty");
            if (e == null) {
                expect("frames: the client has the " + names[i], false, "nothing at x " + x);
                continue;
            }
            if (!wantItem) continue;
            expect("frames: the client has the " + names[i] + " with its item", !synced.isEmpty(), e.getType() + " holding " + synced);
            EntityRenderState state = mc.getEntityRenderDispatcher().extractEntity(e, 1f);
            boolean empty = state instanceof ItemFrameRenderState fs ? fs.item.isEmpty() : state instanceof ItemDisplayEntityRenderState ds ? ds.item.isEmpty()
                : state instanceof ItemEntityRenderState is && is.item.isEmpty();
            String box2 = state instanceof ItemFrameRenderState fs ? String.valueOf(fs.item.getModelBoundingBox()) : state instanceof ItemDisplayEntityRenderState ds
                ? String.valueOf(ds.item.getModelBoundingBox()) : state instanceof ItemEntityRenderState is ? String.valueOf(is.item.getModelBoundingBox()) : "?";
            expect("frames: the " + names[i] + "'s render state holds its item", !empty,
                state.getClass().getSimpleName() + ", item " + (empty ? "empty" : "model " + box2) + ", renderer " + mc.getEntityRenderDispatcher().getRenderer(e).getClass().getName()
                    + ", hidden by NTGL " + ntglHidden(state) + ", light " + state.lightCoords);
        }
        // Create Fly's Vanillin, as shipped, draws the frames and displays itself (instanced): each must be one it takes
        boolean flywheel = VisualizationManager.get(mc.level) != null;
        for (int i = 0; i < names.length; i++) {
            if (names[i].endsWith("empty") || names[i].startsWith("item entity")) continue;
            double x = w.getX() + FramesTests.FRAME_STEP * i + 0.5;
            AABB box = new AABB(x - 0.6, eyeY - 1.2, w.getZ(), x + 0.6, eyeY + 1, w.getZ() + 2.2);
            Entity e = null;
            for (Entity f : mc.level.getEntities((Entity) null, box, f -> f instanceof ItemFrame || f instanceof Display.ItemDisplay)) e = f;
            boolean drawn = e instanceof ItemFrame f ? ItemFrameVisual.shouldVisualize(f) : e instanceof Display.ItemDisplay d && ItemDisplayVisual.shouldVisualize(d);
            expect("frames: Vanillin draws the " + names[i], flywheel && drawn, "visualization " + (flywheel ? "on" : "off") + ", " + (e == null ? "no entity" : e.getType()) + " taken " + drawn);
        }
        // a picture straight at each, as the game draws them
        Map<String, int[]> asIs = new LinkedHashMap<>();
        int[] wall = new int[1];
        pictures(names, eyeY, eyeZ, asIs, wall);
        then(1, () -> {
            int[] empty = asIs.get("frame empty");
            for (String name : names) {
                if (name.equals("frame empty")) continue;
                double changed = changed(name, asIs, empty, wall[0]);
                expect("frames: the picture shows the " + name + "'s item", changed >= 0.12,
                    String.format(Locale.ROOT, "%.0f%% of the middle differs from %s; middle %s, wall %s", changed * 100,
                        name.startsWith("frame") ? "the empty frame" : "the bare wall", mean(asIs.get(name)), hex(wall[0])));
            }
            log("frames checked");
        });
    }

    /** One picture straight at each target, the middle of each kept by name (and the wall above the first). */
    private static void pictures(String[] names, double eyeY, double eyeZ, Map<String, int[]> boxes, int[] wall) {
        BlockPos w = FramesTests.FRAMES;
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            double x = w.getX() + FramesTests.FRAME_STEP * i + 0.5;
            then(i == 0 ? 0 : 1, () -> look(x, eyeY, eyeZ, 180, 0));
            then(25, () -> picture(image -> {
                int cx = image.getWidth() / 2, cy = image.getHeight() / 2;
                boxes.put(name, box(image, cx, cy, 24));
                wall[0] = image.getPixel(cx, cy - 150);
            }));
        }
    }

    /** How much of the middle of a target's picture differs from the empty frame's (frames) or the bare wall (the rest). */
    private static double changed(String name, Map<String, int[]> boxes, int @Nullable [] empty, int wall) {
        int[] at = boxes.get(name);
        if (name.startsWith("frame")) return changed(at, empty, 40);
        int[] bare = new int[at == null ? 0 : at.length];
        Arrays.fill(bare, wall);
        return changed(at, bare, 40);
    }

    /** A box of pixels round (cx, cy), half-size {@code r}. */
    private static int[] box(NativeImage image, int cx, int cy, int r) {
        int[] out = new int[(2 * r) * (2 * r)];
        int k = 0;
        for (int y = cy - r; y < cy + r; y++) for (int x = cx - r; x < cx + r; x++) out[k++] = image.getPixel(x, y);
        return out;
    }

    private static double changed(int @Nullable [] a, int @Nullable [] b, int tol) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return -1;
        int n = 0;
        for (int i = 0; i < a.length; i++) if (differs(a[i], b[i], tol)) n++;
        return (double) n / a.length;
    }

    private static String mean(int @Nullable [] px) {
        if (px == null || px.length == 0) return "none";
        long rr = 0, gg = 0, bb = 0;
        for (int p : px) {
            rr += r(p);
            gg += g(p);
            bb += b(p);
        }
        return String.format(Locale.ROOT, "#%02x%02x%02x", rr / px.length, gg / px.length, bb / px.length);
    }

    private static String hex(int argb) {
        return String.format(Locale.ROOT, "#%06x", argb & 0xffffff);
    }

    /** The NTGL hidden flag on a render state (NTGL hides entities its guns killed), or "no NTGL". */
    private static String ntglHidden(EntityRenderState state) {
        try {
            Class<?> data = Class.forName("com.nukateam.ntgl.client.render.NtglRenderData");
            return String.valueOf(data.getMethod("isHidden", EntityRenderState.class).invoke(null, state));
        } catch (ReflectiveOperationException | LinkageError e) {
            return "no NTGL";
        }
    }
}
