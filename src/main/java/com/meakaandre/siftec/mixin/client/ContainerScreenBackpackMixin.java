package com.meakaandre.siftec.mixin.client;

import com.meakaandre.siftec.backpack.BackpackRows;
import com.meakaandre.siftec.client.BackpackDraw;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Container screens grow to fit the backpack. The window keeps the place the game gives it and grows to the right
 * (for the columns) and downward (for the rows); it only moves left or up as far as it must to stay on screen. The
 * screen's widgets move with it, and those right of or under the main grid move as its slots do. While the screen's
 * own code runs its init and draws its background it sees its original size, so windows that place their parts
 * from their size (Create's) put them where they always do, and {@link BackpackDraw} lets the backpack in. The rest
 * of the time it has the grown size, so clicks on the new cells are inside the window (not a throw), and anything
 * placed beside the window (JEI's list, the recipe book) keeps clear of it.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerScreenBackpackMixin extends Screen implements BackpackDraw.Window {
    @Shadow
    @Final
    @Mutable
    protected int imageHeight;
    @Shadow
    @Final
    @Mutable
    protected int imageWidth;
    @Shadow
    protected int leftPos;
    @Shadow
    protected int topPos;
    @Shadow
    @Final
    protected AbstractContainerMenu menu;

    /** The screen's own size, the size with the backpack, the height its drawing code sees, and what is shown. */
    @Unique
    private int siftec$base = -1, siftec$set = -1, siftec$baseW = -1, siftec$setW = -1, siftec$drawHeight, siftec$extra, siftec$wide, siftec$shown, siftec$lift;
    @Unique
    private boolean siftec$initing;

    protected ContainerScreenBackpackMixin(Component title) {
        super(title);
    }

    @Inject(method = "<init>(Lnet/minecraft/world/inventory/AbstractContainerMenu;Lnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/network/chat/Component;II)V", at = @At("RETURN"))
    private void siftec$attach(AbstractContainerMenu menu, Inventory inventory, Component title, int width, int height, CallbackInfo ci) {
        if (!((Object) this instanceof CreativeModeInventoryScreen)) BackpackRows.attach(menu, inventory.player);
    }

    @Unique
    private int siftec$wanted() {
        if ((Object) this instanceof CreativeModeInventoryScreen || minecraft == null) return 0;
        return BackpackRows.shown(menu, minecraft.player);
    }

    @Inject(method = "init()V", at = @At("HEAD"))
    private void siftec$originalSize(CallbackInfo ci) {
        if (imageHeight != siftec$set) siftec$base = imageHeight;
        if (imageWidth != siftec$setW) siftec$baseW = imageWidth;
        imageHeight = siftec$base;
        imageWidth = siftec$baseW;
        siftec$shown = siftec$wanted();
        siftec$extra = BackpackRows.rows(siftec$shown) * 18;
        siftec$wide = BackpackRows.columns(siftec$shown) * 18;
        BackpackRows.place(BackpackRows.of(menu), siftec$wide / 18, siftec$extra / 18);
        siftec$initing = true;
    }

    @Inject(method = "init()V", at = @At("RETURN"))
    private void siftec$grow(CallbackInfo ci) {
        siftec$initing = false;
        int lift = Math.max(0, Math.min(topPos, topPos + siftec$base + siftec$extra - height));
        int shiftLeft = Math.max(0, Math.min(leftPos, leftPos + siftec$baseW + siftec$wide - width));
        BackpackRows.Layout layout = BackpackRows.of(menu);
        if (layout != null && (lift > 0 || shiftLeft > 0 || siftec$extra > 0 || siftec$wide > 0)) {
            // the lines the slots moved at, in screen coordinates
            int rightOf = leftPos + layout.left + 161, below = topPos + layout.top + 53;
            for (GuiEventListener child : children()) {
                if (!(child instanceof AbstractWidget widget)) continue;
                int x = widget.getX(), y = widget.getY();
                widget.setX(x - shiftLeft + (x >= rightOf ? siftec$wide : 0));
                widget.setY(y - lift + (y >= below ? siftec$extra : 0));
            }
        }
        topPos -= lift;
        leftPos -= shiftLeft;
        siftec$lift = lift;
        siftec$drawHeight = siftec$base + 2 * lift;
        imageHeight = siftec$base + siftec$extra;
        imageWidth = siftec$baseW + siftec$wide;
        siftec$set = imageHeight;
        siftec$setW = imageWidth;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void siftec$followUnlocks(CallbackInfo ci) {
        if (siftec$set < 0) return;
        int shown = siftec$wanted();
        if (BackpackRows.rows(shown) * 18 != siftec$extra || BackpackRows.columns(shown) * 18 != siftec$wide) {
            rebuildWidgets();
        } else {
            siftec$shown = shown;
        }
    }

    @Override
    public int siftec$extra() {
        return siftec$set < 0 ? 0 : siftec$extra;
    }

    @Override
    public int siftec$wide() {
        return siftec$set < 0 ? 0 : siftec$wide;
    }

    @Override
    public int siftec$shown() {
        return siftec$shown;
    }

    @Override
    public int siftec$gridLeft() {
        BackpackRows.Layout layout = BackpackRows.of(menu);
        return layout == null ? 0 : layout.left;
    }

    @Override
    public int siftec$gridTop() {
        BackpackRows.Layout layout = BackpackRows.of(menu);
        return layout == null ? 0 : layout.top;
    }

    @Override
    public int siftec$left() {
        return leftPos;
    }

    @Override
    public int siftec$top() {
        return topPos;
    }

    @Override
    public int siftec$baseWidth() {
        return siftec$baseW < 0 ? imageWidth : siftec$baseW;
    }

    @Override
    public boolean siftec$initing() {
        return siftec$initing;
    }

    @Override
    public void siftec$drawing(boolean drawing) {
        imageHeight = drawing ? siftec$drawHeight : siftec$base + siftec$extra;
        imageWidth = drawing ? siftec$baseW : siftec$baseW + siftec$wide;
    }

    @Override
    public int siftec$lift() {
        return siftec$lift;
    }
}
