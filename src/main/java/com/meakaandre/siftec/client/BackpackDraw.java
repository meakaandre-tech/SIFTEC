package com.meakaandre.siftec.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.meakaandre.siftec.backpack.BackpackRows;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws a window's background with the backpack let in, whatever the window's texture: the background is drawn in
 * slices, as if the window were cut along two lines and pulled apart.
 * <ul>
 * <li>A vertical cut just right of the main grid: everything right of it (the window's right edge and anything else
 * there) is drawn moved right by the backpack columns. The gap is filled, beside the main rows, with copies of the
 * window's own last main column; above and below the rows (title, container part, hotbar band, bottom edge) with the
 * plain one-pixel strip just inside the right edge stretched across, so the top and bottom borders run on.</li>
 * <li>A horizontal cut just under the main rows: everything below it (the hotbar and the bottom edge) is drawn moved
 * down by the backpack rows, and each row is a copy of the window's first main row, made as wide as the grid.</li>
 * </ul>
 * Cells that are not open (a part row or column) are covered with the plain colour of the strip between the main rows
 * and the hotbar. The screen-wide backdrop (blur, dimming) that every window draws first is drawn once, unclipped.
 */
public final class BackpackDraw {
    /** What a container screen tells the drawing (mixed into AbstractContainerScreen). */
    public interface Window {
        /** Height added for the backpack rows, 0 when none show. */
        int siftec$extra();

        /** Width added for the backpack columns, 0 when none show. */
        int siftec$wide();

        /** Backpack slots shown. */
        int siftec$shown();

        /** Window-relative x and y of the first main inventory slot. */
        int siftec$gridLeft();

        int siftec$gridTop();

        int siftec$left();

        int siftec$top();

        /** The window's own width, without the backpack columns. */
        int siftec$baseWidth();

        /** How far the window was moved up to stay on screen. */
        int siftec$lift();

        /** Whether the screen is running its own init (the backpack's moves come after it). */
        boolean siftec$initing();

        /** Grows the window and moves its widgets, once the screen's whole init has run. */
        void siftec$afterInit();

        /** Sets the window size the screen's own drawing code sees (its original size while it draws). */
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
        int wide = window.siftec$wide(), cols = wide / 18;
        int gridX = left + window.siftec$gridLeft() - 1, gridY = top + window.siftec$gridTop() - 1;
        // the vertical cut: beside the rows just right of the grid's frame; above and below them two pixels further,
        // clear of anything the window has hanging over that line (the inventory's crafting result frame)
        int cut = gridX + 162, strip = cut + 2;
        int split = gridY + 54;
        int width = screen.width, bottom = Math.max(screen.height, split + extra + 1);
        // the copied rows stay within the window (and the grid's frame), so nothing drawn beside it is repeated
        int bandX0 = Math.min(left, gridX - 7), bandX1 = Math.max(left + window.siftec$baseWidth(), cut + 7);
        // one plain pixel of the strip between the main rows and the hotbar, to cover cells that are not open
        int plainX = gridX + 9, plainY = split + 1;
        window.siftec$drawing(true);
        try {
            pass = 1;
            slice(screen, graphics, original, 0, 0, strip, gridY, 0, 0, mouseX, mouseY, delta);
            pass = 2;
            slice(screen, graphics, original, 0, gridY, cut, split, 0, 0, mouseX, mouseY, delta);
            slice(screen, graphics, original, strip + wide, 0, width, gridY, wide, 0, mouseX, mouseY, delta);
            slice(screen, graphics, original, cut + wide, gridY, width, split, wide, 0, mouseX, mouseY, delta);
            stretch(screen, graphics, original, strip, 0, strip + wide, gridY, strip, 0, mouseX, mouseY, delta);
            for (int c = 0; c < cols; c++) {
                int x = cut + c * 18;
                // a copy of the last main column
                slice(screen, graphics, original, x, gridY, x + 18, split, (c + 1) * 18, 0, mouseX, mouseY, delta);
                for (int r = 0; r < 3; r++) {
                    if (c * 3 + r >= shown) cover(screen, graphics, original, x, gridY + r * 18, x + 18, gridY + r * 18 + 18, plainX, plainY, mouseX, mouseY, delta);
                }
            }
            for (int k = 0; k < rows; k++) {
                int y = split + k * 18, dy = 54 + k * 18;
                // the first main row's band starts 54 pixels above the split line
                slice(screen, graphics, original, bandX0, y, cut, y + 18, 0, dy, mouseX, mouseY, delta);
                for (int c = 0; c < cols; c++) {
                    slice(screen, graphics, original, cut + c * 18, y, cut + c * 18 + 18, y + 18, (c + 1) * 18, dy, mouseX, mouseY, delta);
                }
                slice(screen, graphics, original, cut + wide, y, bandX1 + wide, y + 18, wide, dy, mouseX, mouseY, delta);
                int open = Math.max(0, Math.min(9 + cols, shown - BackpackRows.COLUMN_SLOTS - k * BackpackRows.WIDTH));
                if (open < 9 + cols) cover(screen, graphics, original, gridX + open * 18, y, cut + wide, y + 18, plainX, plainY, mouseX, mouseY, delta);
            }
            slice(screen, graphics, original, 0, split + extra, strip, bottom, 0, extra, mouseX, mouseY, delta);
            slice(screen, graphics, original, strip + wide, split + extra, width, bottom, wide, extra, mouseX, mouseY, delta);
            stretch(screen, graphics, original, strip, split + extra, strip + wide, bottom, strip, extra, mouseX, mouseY, delta);
        } finally {
            pass = 0;
            window.siftec$drawing(false);
        }
    }

    /** Draws the background clipped to a rectangle, shifted right by {@code dx} and down by {@code dy}. */
    private static void slice(Screen screen, GuiGraphicsExtractor graphics, Operation<Void> original, int x0, int y0, int x1, int y1, int dx, int dy,
                              int mouseX, int mouseY, float delta) {
        draw(screen, graphics, original, x0, y0, x1, y1, 0, 0, dx, dy, 1, 1, mouseX - dx, mouseY - dy, delta);
    }

    /** Fills a rectangle with the one-pixel column {@code srcX} of the background stretched across it, shifted down by {@code dy}. */
    private static void stretch(Screen screen, GuiGraphicsExtractor graphics, Operation<Void> original, int x0, int y0, int x1, int y1, int srcX, int dy,
                                int mouseX, int mouseY, float delta) {
        draw(screen, graphics, original, x0, y0, x1, y1, srcX, 0, x0 - srcX, dy, x1 - x0, 1, mouseX, mouseY, delta);
    }

    /** Fills a rectangle with one pixel of the background. */
    private static void cover(Screen screen, GuiGraphicsExtractor graphics, Operation<Void> original, int x0, int y0, int x1, int y1, int srcX, int srcY,
                              int mouseX, int mouseY, float delta) {
        draw(screen, graphics, original, x0, y0, x1, y1, srcX, srcY, x0 - srcX, y0 - srcY, x1 - x0, y1 - y0, mouseX, mouseY, delta);
    }

    /**
     * Draws the background clipped to a rectangle, with the point ({@code ox}, {@code oy}) moved by ({@code dx},
     * {@code dy}) and the picture scaled by ({@code sx}, {@code sy}) around it.
     */
    private static void draw(Screen screen, GuiGraphicsExtractor graphics, Operation<Void> original, int x0, int y0, int x1, int y1,
                             int ox, int oy, int dx, int dy, int sx, int sy, int mouseX, int mouseY, float delta) {
        if (x1 <= x0 || y1 <= y0) return;
        sx0 = x0;
        sy0 = y0;
        sx1 = x1;
        sy1 = y1;
        graphics.enableScissor(x0, y0, x1, y1);
        graphics.pose().pushMatrix();
        graphics.pose().translate(ox + dx, oy + dy);
        if (sx != 1 || sy != 1) graphics.pose().scale(sx, sy);
        graphics.pose().translate(-ox, -oy);
        try {
            original.call(screen, graphics, mouseX, mouseY, delta);
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
