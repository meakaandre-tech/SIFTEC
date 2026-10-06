package com.meakaandre.siftec.mixin;

import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the server tell a display entity to glide between positions instead of jumping. */
@Mixin(Display.class)
public interface DisplayInvoker {
    @Invoker("setPosRotInterpolationDuration")
    void siftec$glide(int ticks);
}
