package com.meakaandre.siftec.mixin.client;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface ScreenAccessor {
    @Accessor("leftPos")
    int siftec$left();

    @Accessor("topPos")
    int siftec$top();
}
