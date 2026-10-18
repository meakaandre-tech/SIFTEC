package com.meakaandre.siftec.client;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.mixin.client.DebugEntriesInvoker;
import com.meakaandre.siftec.sim.SimStatsPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * The factory simulation's line in the debug overlay (F3): what it runs and the time it takes, as the server sends it
 * once a second ({@link SimStatsPayload}). It is on by default in the overlay; F3 + F6 can switch it off.
 */
public final class SimDebug implements DebugScreenEntry {
    public static final Identifier ID = Siftec.id("factories");
    private static volatile @Nullable SimStatsPayload last;

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(SimStatsPayload.TYPE, (payload, context) -> last = payload);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> last = null);
        try {
            DebugEntriesInvoker.siftec$register(ID, new SimDebug());
        } catch (RuntimeException e) {
            Siftec.LOGGER.warn("SIFTEC: could not add the factory line to the debug overlay", e);
        }
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            try {
                if (client.debugEntries.getStatus(ID) == DebugScreenEntryStatus.NEVER) {
                    client.debugEntries.setStatus(ID, DebugScreenEntryStatus.IN_OVERLAY);
                    client.debugEntries.rebuildCurrentList();
                }
            } catch (RuntimeException e) {
                Siftec.LOGGER.warn("SIFTEC: could not switch the factory line on in the debug overlay", e);
            }
        });
    }

    /** The last figures the server sent, or null. */
    public static @Nullable SimStatsPayload last() {
        return last;
    }

    @Override
    public void display(DebugScreenDisplayer out, @Nullable Level level, @Nullable LevelChunk client, @Nullable LevelChunk server) {
        SimStatsPayload s = last;
        out.addLine(s == null ? "SIFTEC factories: no figures from the server yet" : s.line());
    }

    @Override
    public boolean isAllowed(boolean reducedDebugInfo) {
        return true;
    }

    static boolean shown() {
        Minecraft mc = Minecraft.getInstance();
        return mc.debugEntries != null && mc.debugEntries.isCurrentlyEnabled(ID);
    }
}
