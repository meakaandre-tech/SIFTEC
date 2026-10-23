package com.meakaandre.siftec.client;

import com.meakaandre.siftec.cosmetic.ShopSignBlock;
import com.meakaandre.siftec.cosmetic.ShopSignBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a Shop sign's front text on its face (the panel itself is the block's model): the lines written on it, centred
 * and scaled to fill the face, so a billboard's few words stand metres high. Dye colours the text and glow ink lights it.
 */
public class ShopSignRenderer implements BlockEntityRenderer<ShopSignBlockEntity, ShopSignRenderer.State> {
    private static final int LINE = 10, FULL_BRIGHT = 0xF000F0;
    /** Share of the face's width and height the text may fill (the rest is margin). */
    private static final float FILL_WIDTH = 0.85f, FILL_HEIGHT = 0.8f;
    /** Narrowest text width (font pixels) scaled to, so a short line is not drawn huge. */
    private static final int MIN_TEXT_WIDTH = 24;
    /** Gap between the panel's face and the text, so they do not z-fight (blocks). */
    private static final double TEXT_OFFSET = 0.005;
    /** How far away the sign's text is still drawn (blocks). */
    private static final int VIEW_DISTANCE = 128;
    private final Font font;

    public ShopSignRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.font();
    }

    public static class State extends BlockEntityRenderState {
        public final List<FormattedCharSequence> lines = new ArrayList<>();
        public final List<Integer> widths = new ArrayList<>();
        public int color;
        public boolean glow;
        public float yRot;
        public @Nullable ShopSignBlock block;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(ShopSignBlockEntity sign, State state, float partialTick, Vec3 camera, ModelFeatureRenderer.@Nullable CrumblingOverlay crumbling) {
        BlockEntityRenderState.extractBase(sign, state, crumbling);
        state.lines.clear();
        state.widths.clear();
        BlockState blockState = sign.getBlockState();
        state.block = blockState.getBlock() instanceof ShopSignBlock b ? b : null;
        state.yRot = blockState.hasProperty(WallSignBlock.FACING) ? blockState.getValue(WallSignBlock.FACING).toYRot() : 0;
        SignText text = sign.getText(SignTextSlot.FRONT);
        state.glow = text.hasGlowingText();
        state.color = 0xFF000000 | text.getColor().getTextColor();
        // the written lines, without the empty ones at the top and bottom, so the text sits in the middle of the face
        List<Component> messages = text.getMessages(false);
        int first = 0, last = messages.size() - 1;
        while (first <= last && messages.get(first).getString().isBlank()) first++;
        while (last >= first && messages.get(last).getString().isBlank()) last--;
        for (int i = first; i <= last; i++) {
            FormattedCharSequence line = messages.get(i).getVisualOrderText();
            state.lines.add(line);
            state.widths.add(font.width(line));
        }
    }

    @Override
    public void submit(State state, PoseStack ms, SubmitNodeCollector queue, CameraRenderState camera) {
        ShopSignBlock block = state.block;
        if (block == null || state.lines.isEmpty()) return;
        int widest = 1;
        for (int w : state.widths) widest = Math.max(widest, w);
        int n = state.lines.size();
        // fills the face: as large as the widest line and the lines' height allow, leaving a margin
        float scale = Math.min(block.faceWidth * FILL_WIDTH / Math.max(widest, MIN_TEXT_WIDTH), block.faceHeight * FILL_HEIGHT / (n * LINE));
        ms.pushPose();
        ms.translate(0.5, 0.5, 0.5);
        ms.rotateDegrees(Axis.YP, -state.yRot);
        // the panel lies against the wall behind (the north side as it faces south), 1.5 pixels thick
        ms.translate(block.faceX, block.faceY, -0.5 + ShopSignBlock.PANEL_THICKNESS / 16 + TEXT_OFFSET);
        ms.scale(scale, -scale, scale);
        int light = state.glow ? FULL_BRIGHT : state.lightCoords;
        for (int i = 0; i < n; i++) {
            float x = -state.widths.get(i) / 2f, y = (i - n / 2f) * LINE + 1;
            queue.submitText(ms, x, y, state.lines.get(i), false, Font.DisplayMode.POLYGON_OFFSET, light, state.color, 0, 0);
        }
        ms.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    @Override
    public int getViewDistance() {
        return VIEW_DISTANCE;
    }
}
