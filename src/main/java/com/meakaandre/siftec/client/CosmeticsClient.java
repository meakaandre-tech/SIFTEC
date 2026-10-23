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

    private record Playing(String emote, long start) {
    }

    private CosmeticsClient() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(EmotePayload.TYPE, (payload, context) -> Minecraft.getInstance().execute(
            () -> PLAYING.put(payload.entity(), new Playing(payload.emote(), ticks))));
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
        float n = Float.NaN, rx = n, ry = n, rz = n, lx = n, ly = n, lz = n, head = n, body = n;
        switch (playing.emote) {
            case "wave" -> {
                rx = -2.8f;
                rz = 0.3f + Mth.sin(t * 0.6f) * 0.35f;
            }
            case "point" -> {
                rx = -1.55f;
                ry = -0.15f;
            }
            case "clap" -> {
                float a = 0.25f + 0.35f * Math.abs(Mth.sin(t * 0.7f));
                rx = lx = -1.35f;
                ry = -a;
                ly = a;
            }
            case "facepalm" -> {
                rx = -2.2f;
                ry = 0.55f;
                head = 0.45f;
            }
            case "heart" -> {
                rx = lx = -2.9f;
                rz = 0.55f;
                lz = -0.55f;
            }
            case "dance" -> {
                float s = Mth.sin(t * 0.5f);
                rx = -1.3f + s * 1.3f;
                lx = -1.3f - s * 1.3f;
                body = Mth.sin(t * 0.25f) * 0.3f;
            }
            default -> {
                return;
            }
        }
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
