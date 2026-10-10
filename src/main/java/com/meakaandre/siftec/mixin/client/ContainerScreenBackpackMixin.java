package com.meakaandre.siftec.mixin.client;

import com.meakaandre.siftec.backpack.BackpackRows;
import com.meakaandre.siftec.client.BackpackDraw;
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
 * Container screens grow to fit the backpack rows. The window keeps the place the game gives it and grows downward;
 * it only moves up as far as it must to stay on screen. While the screen's own code draws its background it sees
 * its original height, so windows that place their parts from their height (Create's) draw them where they always
 * do, and {@link BackpackDraw} lets the rows in.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerScreenBackpackMixin extends Screen implements BackpackDraw.Window {
    @Shadow
    @Final
    @Mutable
    protected int imageHeight;
    @Shadow
    @Final
    protected int imageWidth;
    @Shadow
    protected int leftPos;
    @Shadow
    protected int topPos;
    @Shadow
    @Final
    protected AbstractContainerMenu menu;

    /** The screen's own height, the height with the rows, the height its drawing code sees, and the rows shown. */
    @Unique
    private int siftec$base = -1, siftec$set = -1, siftec$drawHeight, siftec$extra, siftec$shown;

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
    private void siftec$originalHeight(CallbackInfo ci) {
        if (imageHeight != siftec$set) siftec$base = imageHeight;
        imageHeight = siftec$base;
        siftec$shown = siftec$wanted();
        siftec$extra = BackpackRows.rows(siftec$shown) * 18;
        BackpackRows.place(BackpackRows.of(menu), siftec$extra / 18);
    }

    @Inject(method = "init()V", at = @At("RETURN"))
    private void siftec$grow(CallbackInfo ci) {
        int lift = Math.max(0, Math.min(topPos, topPos + siftec$base + siftec$extra - height));
        topPos -= lift;
        siftec$drawHeight = siftec$base + 2 * lift;
        imageHeight = siftec$base + siftec$extra;
        siftec$set = imageHeight;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void siftec$followUnlocks(CallbackInfo ci) {
        if (siftec$set < 0) return;
        int shown = siftec$wanted();
        if (BackpackRows.rows(shown) * 18 != siftec$extra) {
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
    public int siftec$imageWidth() {
        return imageWidth;
    }

    @Override
    public void siftec$drawing(boolean drawing) {
        imageHeight = drawing ? siftec$drawHeight : siftec$base + siftec$extra;
    }

    @Override
    public int siftec$lift() {
        return (siftec$drawHeight - siftec$base) / 2;
    }
}
