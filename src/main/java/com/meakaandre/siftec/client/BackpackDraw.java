package com.meakaandre.siftec.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws a window's background with the backpack rows let in, whatever the window's texture: the background is drawn
 * in slices. Everything above the line under the main rows as it is; then one slice per backpack row, copied from
 * the window's own first main row (so the rows look exactly like the window's inventory); then everything below the
 * line (the hotbar and the bottom edge) moved down by the rows. Cells of a part row that are not open are covered
 * with copies of the plain strip between the main rows and the hotbar.
 * <p>
 * The screen-wide backdrop (blur, dimming) that every window draws first is drawn once, unclipped.
 */
public final class BackpackDraw {
    /** What a container screen tells the drawing (mixed into AbstractContainerScreen). */
    public interface Window {
        /** Height added for the backpack rows, 0 when none show. */
        int siftec$extra();

        /** Backpack slots shown. */
        int siftec$shown();

        /** Window-relative x and y of the first main inventory slot. */
        int siftec$gridLeft();

        int siftec$gridTop();

        int siftec$left();

        int siftec$top();

        int siftec$imageWidth();

        /** How far the window was moved up to stay on screen. */
        int siftec$lift();

        /** Sets the window height the screen's own drawing code sees (its original height while it draws). */
        void siftec$drawing(boolean drawing);
    }

    /** 0: not slicing; 1: the top slice (draws the backdrop); 2: the other slices (skip it). */
    public static int pass;
    private static int sx0, sy0, sx1, sy1;
    private static boolean lifted;

    private BackpackDraw() {
    }

    public static void draw(Screen screen, Window window, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, Operation<Void> original) {
        int left = window.siftec$left(), top = window.siftec$top();
        int extra = window.siftec$extra(), rows = extra / 18, shown = window.siftec$shown();
        int gridX = left + window.siftec$gridLeft() - 1;
        int split = top + window.siftec$gridTop() + 53;
        int width = screen.width, height = screen.height;
        // the copied rows stay within the window (and the grid's frame), so nothing drawn beside it is repeated
        int bandX0 = Math.min(left, gridX - 7), bandX1 = Math.max(left + window.siftec$imageWidth(), gridX + 162 + 7);
        window.siftec$drawing(true);
        try {
            pass = 1;
            slice(screen, graphics, original, 0, 0, width, split, 0, mouseX, mouseY, delta);
            pass = 2;
            for (int k = 0; k < rows; k++) {
                int bandTop = split + k * 18;
                // the first main row's band starts 54 pixels above the split line
                slice(screen, graphics, original, bandX0, bandTop, bandX1, bandTop + 18, 54 + k * 18, mouseX, mouseY, delta);
                int open = Math.min(9, shown - k * 9);
                if (open < 9) {
                    for (int j = 0; j < 18; j += 4) {
                        int h = Math.min(4, 18 - j);
                        slice(screen, graphics, original, gridX + open * 18, bandTop + j, gridX + 162, bandTop + j + h, bandTop + j - split, mouseX, mouseY, delta);
                    }
                }
            }
            slice(screen, graphics, original, 0, split + extra, width, Math.max(height, split + extra + 1), extra, mouseX, mouseY, delta);
        } finally {
            pass = 0;
            window.siftec$drawing(false);
        }
    }

    /** Draws the background clipped to a rectangle, shifted down by {@code dy}. */
    private static void slice(Screen screen, GuiGraphicsExtractor graphics, Operation<Void> original, int x0, int y0, int x1, int y1, int dy,
                              int mouseX, int mouseY, float delta) {
        if (x1 <= x0 || y1 <= y0) return;
        sx0 = x0;
        sy0 = y0;
        sx1 = x1;
        sy1 = y1;
        graphics.enableScissor(x0, y0, x1, y1);
        graphics.pose().pushMatrix();
        graphics.pose().translate(0, dy);
        try {
            original.call(screen, graphics, mouseX, mouseY - dy, delta);
        } finally {
            graphics.pose().popMatrix();
            graphics.disableScissor();
        }
    }

    /** Called where Screen's own background (the backdrop) starts. */
    public static void backdropStart(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (pass == 2) {
            ci.cancel();
        } else if (pass == 1 && !lifted) {
            graphics.disableScissor();
            lifted = true;
        }
    }

    /** Called where Screen's own background ends. */
    public static void backdropEnd(GuiGraphicsExtractor graphics) {
        if (lifted) {
            lifted = false;
            graphics.enableScissor(sx0, sy0, sx1, sy1);
        }
    }
}
