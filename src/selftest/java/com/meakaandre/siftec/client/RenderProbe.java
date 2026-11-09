package com.meakaandre.siftec.client;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * For working out why some items are not drawn in the world on the CI client: while it is on (a few frames), the probe
 * mixins note every item an entity or block entity submits (who, where, how lit), every item that reaches the frame's
 * queue, and every item the item feature renderer prepares to draw; and which belt blocks Create's belt renderer is
 * asked about, extracts and submits. {@link #summary} says it in one line each.
 */
public final class RenderProbe {
    private static volatile boolean on;
    private static final Map<String, Integer> SUBMITTED = new LinkedHashMap<>();
    private static final List<String> SUBMITS = new ArrayList<>(), QUEUED = new ArrayList<>(), PREPARED = new ArrayList<>();
    private static final Map<String, Integer> BELTS = new LinkedHashMap<>();
    private static final Set<String> RENDERERS = Set.of("net.minecraft.client.renderer.entity.", "net.minecraft.client.renderer.blockentity.",
        "com.zurrtum.create.client.", "net.minecraft.client.renderer.ItemInHandRenderer", "net.minecraft.client.gui.");

    private RenderProbe() {
    }

    public static void start() {
        synchronized (RenderProbe.class) {
            SUBMITTED.clear();
            SUBMITS.clear();
            QUEUED.clear();
            PREPARED.clear();
            BELTS.clear();
        }
        on = true;
    }

    public static void stop() {
        on = false;
    }

    public static boolean on() {
        return on;
    }

    /** ItemStackRenderState.submit: who called it, where the item is and how it is lit. */
    public static void itemSubmitted(PoseStack ms, int light) {
        if (!on) return;
        String who = StackWalker.getInstance().walk(s -> s.map(f -> f.getClassName())
            .filter(c -> !c.startsWith("net.minecraft.client.renderer.item.") && RENDERERS.stream().anyMatch(c::startsWith)).findFirst().orElse("?"));
        who = who.substring(who.lastIndexOf('.') + 1);
        synchronized (RenderProbe.class) {
            SUBMITTED.merge(who, 1, Integer::sum);
            if (SUBMITS.size() < 24) SUBMITS.add(who + " at " + where(ms.last().pose()) + " light " + Integer.toHexString(light));
        }
    }

    /** SubmitNodeCollection.submitItem: the item reached the frame's queue. */
    public static void itemQueued(PoseStack ms, int quads) {
        if (!on) return;
        synchronized (RenderProbe.class) {
            if (QUEUED.size() < 24) QUEUED.add(where(ms.last().pose()) + " quads " + quads);
        }
    }

    /** ItemFeatureRenderer.prepareMainSubmit: the item is being drawn. */
    public static void itemPrepared(Matrix4f pose, int light, int quads, String context) {
        if (!on) return;
        synchronized (RenderProbe.class) {
            if (PREPARED.size() < 24) PREPARED.add(where(pose) + " light " + Integer.toHexString(light) + " quads " + quads + " " + context);
        }
    }

    /** Create's belt renderer: asked (and the answer), extracted, submitted, by whether the block is the belt's controller. */
    public static void belt(String what, boolean controller) {
        if (!on) return;
        synchronized (RenderProbe.class) {
            BELTS.merge(what + (controller ? " controller" : " other"), 1, Integer::sum);
        }
    }

    private static String where(Matrix4f pose) {
        Vector3f t = pose.getTranslation(new Vector3f()), s = pose.getScale(new Vector3f());
        return String.format(Locale.ROOT, "(%.2f %.2f %.2f) scale (%.3f %.3f %.3f)", t.x, t.y, t.z, s.x, s.y, s.z);
    }

    public static List<String> summary() {
        synchronized (RenderProbe.class) {
            List<String> out = new ArrayList<>();
            out.add("items submitted by " + SUBMITTED);
            out.add("submits " + SUBMITS);
            out.add("queued " + QUEUED.size() + (QUEUED.size() >= 24 ? "+" : "") + " " + QUEUED);
            out.add("prepared to draw " + PREPARED.size() + (PREPARED.size() >= 24 ? "+" : "") + " " + PREPARED);
            out.add("belt renderer " + BELTS);
            return out;
        }
    }
}
