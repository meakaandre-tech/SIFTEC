package com.meakaandre.siftec.client;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.sim.BeltPolishTests;
import com.mojang.blaze3d.platform.NativeImage;
import com.zurrtum.create.AllBlocks;
import com.zurrtum.create.client.catnip.gui.ScreenOpener;
import com.zurrtum.create.client.ponder.foundation.PonderIndex;
import com.zurrtum.create.client.ponder.foundation.PonderScene;
import com.zurrtum.create.client.ponder.foundation.ui.PonderUI;
import com.zurrtum.create.content.kinetics.belt.BeltBlock;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.content.kinetics.belt.BeltSlope;
import com.zurrtum.create.content.logistics.funnel.BeltFunnelBlock;
import com.zurrtum.create.content.logistics.tunnel.BeltTunnelBlock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * The belt polish's checks that only the client can make, driven by {@link BeltPolishTests.Probe} from
 * {@code /siftec selftest beltpolish client <check>}. Each check logs "SELFTEST beltpolish-client PASS/FAIL ..." and then
 * "... <check> checked"; {@code done} logs "... done: N passed, M failed".
 * <ul>
 * <li>{@code vertical}: the vertical belt seen from the side, against a white room, measured in the picture: how deep it
 * is against the black block one block wide above it.</li>
 * <li>{@code sloped}: every sloped belt of the test site draws the side trim of its Mk and caps at its ends, everything
 * inside its own cells.</li>
 * <li>{@code frames}: item frames and item displays on a white wall: the client has their items, their render states hold
 * them, and the picture shows them (an empty frame and the bare wall to compare with). Logs which mixins touch the
 * renderers involved, to say where a missing item is lost.</li>
 * <li>{@code ponder}: Create's Ponder scenes for funnels, tunnels and belts draw each funnel or tunnel standing on a belt
 * as built into the belt's block.</li>
 * </ul>
 */
public final class BeltPolishClientTests {
    private static final String P = "SELFTEST beltpolish-client ";
    private static int passed, failed;
    /** Steps waiting for their tick, and the tick count. */
    private static final Deque<Runnable> QUEUE = new ArrayDeque<>();
    private static int wait, ticks;
    private static final String[] PONDER_ITEMS = {"create:andesite_funnel", "create:brass_funnel", "create:andesite_tunnel", "create:brass_tunnel", "create:belt_connector"};

    private BeltPolishClientTests() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(BeltPolishTests.Probe.TYPE, (payload, context) -> Minecraft.getInstance().execute(() -> probe(payload)));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            ticks++;
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

    private static void probe(BeltPolishTests.Probe probe) {
        try {
            switch (probe.action()) {
                case "vertical" -> vertical();
                case "sloped" -> sloped();
                case "flat" -> flat();
                case "frames" -> frames();
                case "ponder" -> ponder();
                case "ponderopen" -> ponderOpen(probe.arg());
                case "ponderclose" -> Minecraft.getInstance().gui.setScreen(null);
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

    // ------------------------------------------------------------------------------------------------ vertical

    /**
     * The side view of the vertical belt: the belt is the second shape from the top, the black block one block wide the
     * first; the belt must be no more than its 5 px rails deep (a full-depth belt measures about two thirds of the block).
     */
    private static void vertical() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        BlockState belt = mc.level.getBlockState(BeltPolishTests.VERTICAL);
        double[] eye = BeltPolishTests.verticalEye(belt);
        then(0, () -> look(eye[0], eye[1], eye[2], (float) eye[3], (float) eye[4]));
        then(40, () -> picture(image -> {
            int w = image.getWidth(), h = image.getHeight(), x0 = w / 4, x1 = 3 * w / 4;
            int[] count = new int[h];
            for (int y = 0; y < h; y++) {
                // the room's white is most of every row (walls and floor are shaded differently): each row's own median
                int bg = medianColour(image, y, x0, x1);
                for (int x = x0; x < x1; x++) if (differs(image.getPixel(x, y), bg, 40)) count[y]++;
            }
            List<int[]> segs = segments(count, 8);
            String detail = "belt " + belt + ", shapes " + segs.stream().map(s -> s[0] + "-" + s[1] + ":" + median(count, s)).toList();
            if (segs.size() < 2) {
                expect("vertical: from the side, the vertical belt is a thin strip on its back face", false, "not two shapes in the picture; " + detail);
            } else {
                int ref = median(count, segs.get(0)), depth = median(count, segs.get(1));
                double ratio = ref == 0 ? 9 : (double) depth / ref;
                expect("vertical: from the side, the vertical belt is a thin strip on its back face (its depth against a block: at most 0.45, 5 px rails are 0.31)",
                    ratio <= 0.45, String.format(Locale.ROOT, "%d px deep against %d px, %.2f; %s", depth, ref, ratio, detail));
            }
            log("vertical checked");
        }));
    }

    /** The median colour, channel by channel, of a row's pixels from x0 to x1. */
    private static int medianColour(NativeImage image, int y, int x0, int x1) {
        int n = x1 - x0;
        int[] rr = new int[n], gg = new int[n], bb = new int[n];
        for (int x = x0; x < x1; x++) {
            int p = image.getPixel(x, y);
            rr[x - x0] = r(p);
            gg[x - x0] = g(p);
            bb[x - x0] = b(p);
        }
        Arrays.sort(rr);
        Arrays.sort(gg);
        Arrays.sort(bb);
        return 0xff000000 | rr[n / 2] << 16 | gg[n / 2] << 8 | bb[n / 2];
    }

    /** Runs of rows with something in them, at least {@code least} rows high (small gaps bridged), top first. */
    private static List<int[]> segments(int[] count, int least) {
        List<int[]> out = new ArrayList<>();
        int start = -1, gap = 0;
        for (int y = 0; y <= count.length; y++) {
            boolean on = y < count.length && count[y] > 1;
            if (on) {
                if (start < 0) start = y;
                gap = 0;
            } else if (start >= 0 && ++gap > 3) {
                int end = y - gap;
                if (end - start + 1 >= least) out.add(new int[]{start, end});
                start = -1;
                gap = 0;
            }
        }
        return out;
    }

    /** The median width of a shape's middle half. */
    private static int median(int[] count, int[] seg) {
        int len = seg[1] - seg[0] + 1, a = seg[0] + len / 4, z = seg[1] - len / 4;
        int[] v = Arrays.copyOfRange(count, a, Math.max(a + 1, z + 1));
        Arrays.sort(v);
        return v[v.length / 2];
    }

    // ------------------------------------------------------------------------------------------------ sloped

    /**
     * Every sloped belt of the site (one for each belt Mk): each cell draws its Mk's side trim, the first and last cell an
     * end cap, and nothing of the trim or caps leaves the cell. Read through LowBeltRender's {@code look} (by name, so this
     * check also runs against a build without it, where it fails).
     */
    private static void sloped() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        String[] trims = {"ANDESITE", "ANDESITE", "COPPER", "COPPER", "BRASS", "STEEL"};
        Method lookOf = null;
        try {
            lookOf = Class.forName("com.meakaandre.siftec.client.belt.LowBeltRender").getMethod("look", BeltBlockEntity.class);
        } catch (ReflectiveOperationException e) {
            Siftec.LOGGER.debug("no LowBeltRender.look", e);
        }
        for (int mk = 1; mk <= 6; mk++) {
            BlockPos a = BeltPolishTests.SLOPES.south(BeltPolishTests.SLOPE_GAP * (mk - 1));
            List<String> cells = new ArrayList<>();
            boolean ok = lookOf != null;
            int caps = 0, sloped = 0;
            for (int i = 0; i <= BeltPolishTests.SLOPE_RUN; i++) {
                BlockPos at = a.offset(i, i, 0);
                if (!(mc.level.getBlockEntity(at) instanceof BeltBlockEntity be)) {
                    ok = false;
                    cells.add(i + ": no belt");
                    continue;
                }
                BeltSlope slope = be.getBlockState().getValue(BeltBlock.SLOPE);
                if (slope != BeltSlope.HORIZONTAL) sloped++;
                if (lookOf == null) continue;
                Map<String, Object> look = record(invoke(lookOf, be));
                String trim = String.valueOf(look.get("trim"));
                boolean cap = Boolean.TRUE.equals(look.get("cap"));
                float[] min = (float[]) look.get("min"), max = (float[]) look.get("max");
                boolean inside = min != null && max != null && min[0] >= -1e-3 && min[1] >= -1e-3 && min[2] >= -1e-3 && max[0] <= 1.001 && max[1] <= 1.001 && max[2] <= 1.001;
                boolean end = i == 0 || i == BeltPolishTests.SLOPE_RUN;
                if (cap) caps++;
                ok &= trims[mk - 1].equals(trim) && inside && cap == end;
                cells.add(i + ": " + slope + " trim " + trim + (cap ? " cap" : "") + " in " + (min == null ? "?" : fmt(min) + ".." + fmt(max)));
            }
            expect("sloped: a Mk." + mk + " sloped belt draws " + trims[mk - 1] + " side trim, end caps at its ends, all inside its own cells",
                ok && caps == 2 && sloped > 0, (lookOf == null ? "no look in this build; " : "") + cells);
        }
        log("sloped checked");
    }

    /**
     * The Mk.1 to Mk.6 flat belts: every block draws its Mk's side trim (not only the block Create's renderer runs for),
     * the end blocks a cap, and no belt block hides the ground's face under it.
     */
    private static void flat() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        String[] trims = {"ANDESITE", "ANDESITE", "COPPER", "COPPER", "BRASS", "STEEL"};
        Method lookOf = null;
        try {
            lookOf = Class.forName("com.meakaandre.siftec.client.belt.LowBeltRender").getMethod("look", BeltBlockEntity.class);
        } catch (ReflectiveOperationException e) {
            Siftec.LOGGER.debug("no LowBeltRender.look", e);
        }
        for (int mk = 1; mk <= 6; mk++) {
            BlockPos a = BeltPolishTests.MK_ROW.south(BeltPolishTests.MK_GAP * (mk - 1));
            List<String> cells = new ArrayList<>();
            boolean ok = lookOf != null;
            for (int i = 0; i < BeltPolishTests.MK_LENGTH; i++) {
                BlockPos at = a.east(i);
                if (!(mc.level.getBlockEntity(at) instanceof BeltBlockEntity be)) {
                    ok = false;
                    cells.add(i + ": no belt");
                    continue;
                }
                boolean hides = !be.getBlockState().getOcclusionShape().isEmpty();
                Map<String, Object> look = lookOf == null ? Map.of() : record(invoke(lookOf, be));
                String trim = String.valueOf(look.get("trim"));
                boolean cap = Boolean.TRUE.equals(look.get("cap")), end = i == 0 || i == BeltPolishTests.MK_LENGTH - 1;
                ok &= trims[mk - 1].equals(trim) && cap == end && !hides;
                cells.add(i + ": trim " + trim + (cap ? " cap" : "") + (hides ? " hides the ground" : ""));
            }
            expect("flat: every block of a Mk." + mk + " belt draws " + trims[mk - 1] + " side trim, caps at its ends, and none hides the ground under it",
                ok, (lookOf == null ? "no look in this build; " : "") + cells);
        }
        // which belt blocks Create's renderer runs for (in view: the six belts)
        then(0, RenderProbe::start);
        then(3, () -> {
            RenderProbe.stop();
            log("probe: " + RenderProbe.summary().getLast());
            log("flat checked");
        });
    }

    private static @Nullable Object invoke(Method m, Object arg) {
        try {
            return m.invoke(null, arg);
        } catch (ReflectiveOperationException e) {
            Siftec.LOGGER.warn(P + "could not read " + m.getName(), e);
            return null;
        }
    }

    /** A record's components by name (an empty map for null). */
    private static Map<String, Object> record(@Nullable Object rec) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (rec == null || !rec.getClass().isRecord()) return out;
        for (RecordComponent c : rec.getClass().getRecordComponents()) {
            try {
                out.put(c.getName(), c.getAccessor().invoke(rec));
            } catch (ReflectiveOperationException e) {
                out.put(c.getName(), null);
            }
        }
        return out;
    }

    private static String fmt(float[] v) {
        return String.format(Locale.ROOT, "(%.3f %.3f %.3f)", v[0], v[1], v[2]);
    }

    // ------------------------------------------------------------------------------------------------ frames

    /**
     * The frames site: what the client holds and extracts for each item frame and item display (and a loose diamond),
     * then a picture straight at each, compared with the empty frame or the bare wall.
     */
    private static void frames() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        BlockPos w = BeltPolishTests.FRAMES;
        double eyeY = w.getY() + BeltPolishTests.FRAME_HEIGHT + 0.5, eyeZ = w.getZ() + 1 + 2.5;
        String[] names = BeltPolishTests.FRAME_TARGETS;
        // the mixins in the game that touch what draws an item frame's or item display's item (what the log needs if one is missing)
        for (String cls : new String[]{"net.minecraft.client.renderer.entity.ItemFrameRenderer", "net.minecraft.client.renderer.entity.DisplayRenderer",
            "net.minecraft.client.renderer.entity.DisplayRenderer$ItemDisplayRenderer", "net.minecraft.client.renderer.entity.EntityRenderer",
            "net.minecraft.client.renderer.entity.EntityRenderDispatcher", "net.minecraft.client.renderer.entity.ItemEntityRenderer",
            "net.minecraft.client.renderer.item.ItemModelResolver", "net.minecraft.client.renderer.item.ItemStackRenderState",
            "net.minecraft.client.renderer.item.ItemStackRenderState$LayerRenderState", "net.minecraft.client.renderer.LevelRenderer",
            "net.minecraft.client.renderer.SubmitNodeStorage", "net.minecraft.client.renderer.feature.ItemFeatureRenderer"})
            log("mixins in " + cls + ": " + mixins(cls));
        // what the client has
        for (int i = 0; i < names.length; i++) {
            double x = w.getX() + BeltPolishTests.FRAME_STEP * i + 0.5;
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
        // a picture straight at each
        Map<String, int[]> boxes = new LinkedHashMap<>();
        int[] wall = new int[1];
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            double x = w.getX() + BeltPolishTests.FRAME_STEP * i + 0.5;
            then(i == 0 ? 0 : 1, () -> look(x, eyeY, eyeZ, 180, 0));
            then(25, () -> picture(image -> {
                int cx = image.getWidth() / 2, cy = image.getHeight() / 2;
                boxes.put(name, box(image, cx, cy, 24));
                wall[0] = image.getPixel(cx, cy - 150);
            }));
        }
        then(1, () -> {
            int[] empty = boxes.get("frame empty");
            for (String name : names) {
                if (name.equals("frame empty")) continue;
                int[] at = boxes.get(name);
                double changed;
                String against;
                if (name.startsWith("frame")) {
                    changed = changed(at, empty, 40);
                    against = "the empty frame";
                } else {
                    int[] bare = new int[at == null ? 0 : at.length];
                    Arrays.fill(bare, wall[0]);
                    changed = changed(at, bare, 40);
                    against = "the bare wall";
                }
                expect("frames: the picture shows the " + name + "'s item", changed >= 0.12,
                    String.format(Locale.ROOT, "%.0f%% of the middle differs from %s; middle %s, wall %s", changed * 100, against, mean(at), hex(wall[0])));
            }
            // where an item is lost on its way to the screen: a few frames of the render probe, looking at the diamond's frame
            double x = w.getX() + 0.5;
            then(0, () -> look(x, eyeY, eyeZ, 180, 0));
            then(20, RenderProbe::start);
            then(3, () -> {
                RenderProbe.stop();
                for (String line : RenderProbe.summary()) log("probe: " + line);
                log("graphics preset " + mc.options.graphicsPreset().get() + ", improved transparency " + mc.options.improvedTransparency().get());
                // and the same picture with improved transparency the other way round
                mc.options.improvedTransparency().set(!mc.options.improvedTransparency().get());
            });
            then(40, () -> picture(image -> {
                int[] at = box(image, image.getWidth() / 2, image.getHeight() / 2, 24);
                log(String.format(Locale.ROOT, "probe: with improved transparency %s the diamond's frame differs %.0f%% from the empty one",
                    mc.options.improvedTransparency().get(), changed(at, empty, 40) * 100));
                mc.options.improvedTransparency().set(!mc.options.improvedTransparency().get());
            }));
            then(20, () -> log("frames checked"));
        });
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

    /** The mixins merged into a class (from the annotations Mixin leaves on what it merges), or why there are none. */
    static String mixins(String className) {
        try {
            Class<?> cls = Class.forName(className);
            @SuppressWarnings("unchecked")
            Class<? extends Annotation> merged = (Class<? extends Annotation>) Class.forName("org.spongepowered.asm.mixin.transformer.meta.MixinMerged");
            Method mixin = merged.getMethod("mixin");
            TreeSet<String> out = new TreeSet<>();
            for (Method m : cls.getDeclaredMethods()) {
                Annotation a = m.getAnnotation(merged);
                if (a != null) out.add(String.valueOf(mixin.invoke(a)));
            }
            for (var f : cls.getDeclaredFields()) {
                Annotation a = f.getAnnotation(merged);
                if (a != null) out.add(String.valueOf(mixin.invoke(a)));
            }
            return out.isEmpty() ? "none" : out.toString();
        } catch (ReflectiveOperationException | LinkageError e) {
            return "could not read (" + e + ")";
        }
    }

    // ------------------------------------------------------------------------------------------------ ponder

    /**
     * Create's own Ponder scenes for funnels, tunnels and belts: every funnel or tunnel standing on a belt, at the start
     * of each scene and at its end, is drawn built into the belt's block (read through PonderBuiltIn by name), and the
     * hooks that draw it so are in Ponder's renderer.
     */
    private static void ponder() {
        Minecraft mc = Minecraft.getInstance();
        Method lowered = null;
        try {
            lowered = Class.forName("com.meakaandre.siftec.client.belt.PonderBuiltIn").getMethod("lowered", Level.class, BlockPos.class);
        } catch (ReflectiveOperationException e) {
            Siftec.LOGGER.debug("no PonderBuiltIn", e);
        }
        int total = 0;
        for (String id : PONDER_ITEMS) {
            List<String> seen = new ArrayList<>();
            boolean ok = true;
            int found = 0;
            try {
                List<PonderScene> scenes = PonderIndex.getSceneAccess().compile(Identifier.parse(id));
                for (PonderScene scene : scenes) {
                    scene.begin();
                    int[] start = stacked(scene, lowered);
                    scene.seekToTime(mc, 2000);
                    int[] end = stacked(scene, lowered);
                    found += start[0] + end[0];
                    ok &= start[0] == start[1] && end[0] == end[1];
                    seen.add(scene.getId().getPath() + " start " + start[1] + "/" + start[0] + ", end " + end[1] + "/" + end[0]);
                }
                ok &= !scenes.isEmpty();
            } catch (RuntimeException e) {
                Siftec.LOGGER.error(P + "Ponder scenes of {} failed", id, e);
                ok = false;
                seen.add("threw " + e);
            }
            total += found;
            expect("ponder: " + id + "'s scenes draw each funnel and tunnel on a belt built into the belt (built in / on a belt)",
                ok && lowered != null, (lowered == null ? "no PonderBuiltIn in this build; " : "") + seen);
        }
        String hooks = mixins("com.zurrtum.create.client.ponder.foundation.element.WorldSectionElementImpl");
        expect("ponder: Ponder's renderer has the hooks that draw them built in, and the scenes have some", hooks.contains("siftec") && total > 0,
            total + " on belts; " + hooks);
        log("ponder checked");
    }

    /** Funnels and tunnels standing on a belt in the scene's level, and how many of them are drawn built in. */
    private static int[] stacked(PonderScene scene, @Nullable Method lowered) {
        Level level = scene.getLevel();
        BoundingBox b = scene.getBounds();
        int n = 0, in = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = b.minX(); x <= b.maxX(); x++)
            for (int y = b.minY(); y <= b.maxY(); y++)
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    BlockState s = level.getBlockState(pos.set(x, y, z));
                    if (!(s.getBlock() instanceof BeltFunnelBlock) && !(s.getBlock() instanceof BeltTunnelBlock)) continue;
                    if (!level.getBlockState(pos.below()).is(AllBlocks.BELT)) continue;
                    n++;
                    if (lowered != null) {
                        try {
                            if (Boolean.TRUE.equals(lowered.invoke(null, level, pos.immutable()))) in++;
                        } catch (ReflectiveOperationException e) {
                            Siftec.LOGGER.warn(P + "PonderBuiltIn.lowered failed", e);
                        }
                    }
                }
        return new int[]{n, in};
    }

    /** For the screenshots: Create's Ponder for an item, at one of its scenes and a moment of it ("item scene time"). */
    private static void ponderOpen(String arg) {
        String[] a = arg.split(" ");
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(a[0])));
        int scene = Integer.parseInt(a[1]), time = Integer.parseInt(a[2]);
        PonderUI ui = PonderUI.of(stack);
        try {
            var index = PonderUI.class.getDeclaredField("index");
            index.setAccessible(true);
            index.setInt(ui, scene);
            var lazy = PonderUI.class.getDeclaredField("lazyIndex");
            lazy.setAccessible(true);
            Object lerp = lazy.get(ui);
            lerp.getClass().getMethod("startWithValue", double.class).invoke(lerp, (double) scene);
        } catch (ReflectiveOperationException e) {
            log("ponder: could not pick scene " + scene + " (" + e + ")");
        }
        ScreenOpener.transitionTo(ui);
        then(10, () -> {
            if (Minecraft.getInstance().gui.screen() instanceof PonderUI open) open.seekToTime(time);
            log("ponder open " + arg);
        });
    }

    /** For the log: whatever is at a block entity (used while working out a failure). */
    static String describe(@Nullable BlockEntity be) {
        return be == null ? "none" : be.getType() + " " + be.getBlockState();
    }
}
