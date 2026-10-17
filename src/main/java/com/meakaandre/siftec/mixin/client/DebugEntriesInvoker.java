package com.meakaandre.siftec.mixin.client;

import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Adds an entry (the factory simulation's line) to the debug overlay's list, whose own registering is private. */
@Mixin(DebugScreenEntries.class)
public interface DebugEntriesInvoker {
    @Invoker("register")
    static Identifier siftec$register(Identifier id, DebugScreenEntry entry) {
        throw new AssertionError();
    }
}
