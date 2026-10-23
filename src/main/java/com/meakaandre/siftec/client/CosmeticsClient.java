package com.meakaandre.siftec.client;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.cosmetic.EmotePayload;
import com.meakaandre.siftec.cosmetic.Emotes;
import com.meakaandre.siftec.cosmetic.GoldenCart;
import com.mojang.math.Quadrant;
import com.zurrtum.create.AllBlocks;
import com.zurrtum.create.content.contraptions.mounted.CartAssemblerBlock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.Variant;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The client side of the AWESOME Shop's cosmetics: players' emotes (their arms moved through it as the model is posed)
 * and the Golden Cart Assembler (a golden Cart Assembler is drawn with gold models, turned as Create's are).
 */
public final class CosmeticsClient {
    /** entity id -> the emote it plays and the client tick it began */
    private static final Map<Integer, Playing> PLAYING = new HashMap<>();
    private static long ticks;

    private record Playing(@Nullable Emote emote, long start) {
    }

    /** Where an emote puts the arms, head and body at a time (KEEP: left as the game posed it). */
    private record Pose(float rx, float ry, float rz, float lx, float ly, float lz, float head, float body) {
    }

    /** An emote the client can play (siftec_content.json "emotes"), with the pose it moves through. */
    private enum Emote {
        WAVE("wave", t -> new Pose(-2.8f, KEEP, 0.3f + Mth.sin(t * 0.6f) * 0.35f, KEEP, KEEP, KEEP, KEEP, KEEP)),
        POINT("point", t -> new Pose(-1.55f, -0.15f, KEEP, KEEP, KEEP, KEEP, KEEP, KEEP)),
        CLAP("clap", t -> {
            float a = 0.25f + 0.35f * Math.abs(Mth.sin(t * 0.7f));
            return new Pose(-1.35f, -a, KEEP, -1.35f, a, KEEP, KEEP, KEEP);
        }),
        FACEPALM("facepalm", t -> new Pose(-2.2f, 0.55f, KEEP, KEEP, KEEP, KEEP, 0.45f, KEEP)),
        HEART("heart", t -> new Pose(-2.9f, KEEP, 0.55f, -2.9f, KEEP, -0.55f, KEEP, KEEP)),
        DANCE("dance", t -> {
            float s = Mth.sin(t * 0.5f);
            return new Pose(-1.3f + s * 1.3f, KEEP, KEEP, -1.3f - s * 1.3f, KEEP, KEEP, KEEP, Mth.sin(t * 0.25f) * 0.3f);
        });

        private final String id;
        private final Motion pose;

        Emote(String id, Motion pose) {
            this.id = id;
            this.pose = pose;
        }

        /** The emote with this id, or null for one this client cannot play. */
        static @Nullable Emote find(String id) {
            for (Emote e : values()) if (e.id.equals(id)) return e;
            return null;
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /** An emote's pose at a time (the player's age in ticks). */
    @FunctionalInterface
    private interface Motion {
        Pose at(float t);
    }

    /** A part of the pose the emote leaves as the game set it. */
    private static final float KEEP = Float.NaN;

    private CosmeticsClient() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(EmotePayload.TYPE, (payload, context) -> Minecraft.getInstance().execute(
            () -> PLAYING.put(payload.entity(), new Playing(Emote.find(payload.emote()), ticks))));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ticks++;
            PLAYING.values().removeIf(p -> ticks - p.start > Emotes.TICKS);
        });
        ModelLoadingPlugin.register(context -> context.modifyBlockModelOnLoad().register((model, ctx) -> {
            BlockState state = ctx.state();
            if (!state.is(AllBlocks.CART_ASSEMBLER) || !state.hasProperty(GoldenCart.GOLDEN) || !state.getValue(GoldenCart.GOLDEN)) return model;
            return new SingleVariant.Unbaked(new Variant(Siftec.id("block/golden_cart_assembler/" + goldenModel(state))).withYRot(goldenTurn(state))).asRoot();
        }));
    }

    /** Create's model for this Cart Assembler state: block_<rail type>, "_powered" when powered. */
    static String goldenModel(BlockState state) {
        return "block_" + state.getValue(CartAssemblerBlock.RAIL_TYPE).getSerializedName() + (state.getValue(CartAssemblerBlock.POWERED) ? "_powered" : "");
    }

    /** And how Create turns it: by its rail's way, and round if it runs backwards. */
    static Quadrant goldenTurn(BlockState state) {
        boolean eastWest = state.getValue(CartAssemblerBlock.RAIL_SHAPE) == RailShape.EAST_WEST, back = state.getValue(CartAssemblerBlock.BACKWARDS);
        if (eastWest) return back ? Quadrant.R90 : Quadrant.R270;
        return back ? Quadrant.R180 : Quadrant.R0;
    }

    /** Moves a player's arms (and head) through the emote they play, if any. */
    public static void pose(PlayerModel model, AvatarRenderState state) {
        Playing playing = PLAYING.get(state.id);
        if (playing == null) return;
        float t = state.ageInTicks;
        // eases in and out over the first and last few ticks
        float age = ticks - playing.start, w = Mth.clamp(Math.min(age, Emotes.TICKS - age) / 6f, 0, 1);
        if (playing.emote == null) return;
        Pose p = playing.emote.pose.at(t);
        float rx = p.rx(), ry = p.ry(), rz = p.rz(), lx = p.lx(), ly = p.ly(), lz = p.lz(), head = p.head(), body = p.body();
        // only what the emote moves: the rest of the pose stays as the game set it
        model.rightArm.xRot = to(w, model.rightArm.xRot, rx);
        model.rightArm.yRot = to(w, model.rightArm.yRot, ry);
        model.rightArm.zRot = to(w, model.rightArm.zRot, rz);
        model.leftArm.xRot = to(w, model.leftArm.xRot, lx);
        model.leftArm.yRot = to(w, model.leftArm.yRot, ly);
        model.leftArm.zRot = to(w, model.leftArm.zRot, lz);
        model.head.xRot = to(w, model.head.xRot, head);
        model.body.yRot = to(w, model.body.yRot, body);
    }

    private static float to(float w, float now, float want) {
        return Float.isNaN(want) ? now : Mth.lerp(w, now, want);
    }
}
