package com.meakaandre.siftec.mixin.client;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a container screen's panel is, for buttons placed next to it. */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {
    @Accessor("leftPos")
    int siftec$leftPos();

    @Accessor("topPos")
    int siftec$topPos();
}
