package com.meakaandre.siftec.mixin;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractContainerMenu.class)
public interface MenuInvoker {
    @Invoker("addSlot")
    Slot siftec$addSlot(Slot slot);
}
