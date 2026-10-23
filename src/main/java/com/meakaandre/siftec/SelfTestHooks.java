package com.meakaandre.siftec;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Where the selftest mod (siftec_selftest, built only for CI) plugs in. The mod's initializers call {@link #run} at
 * fixed points so the selftests' commands and tick listeners register in the same order among the mod's own as they
 * always have; without the selftest mod installed nothing happens.
 */
public final class SelfTestHooks {
    /** The points in initialization the selftests register at, in the order they come. */
    public enum Stage {
        /** Start of {@code SiftecCommands.register}. */
        COMMANDS,
        /** In {@code Claims.register}, after its chunk-load listener. */
        CLAIMS,
        /** In {@code TravelersBackpackCompat.register}, only when Traveler's Backpack is installed. */
        BACKPACK,
        /** After vehicles, before the power grids and the factory simulation. */
        CONTENT,
        /** After the factory simulation. */
        SIMULATION,
        /** After drones, before emotes. */
        LATE,
        /** End of the client initializer. */
        CLIENT
    }

    /** A selftest entrypoint ({@code siftec:selftest}, or {@code siftec:selftest_client} for {@link Stage#CLIENT}). */
    public interface Plugin {
        void register(Stage stage);
    }

    private SelfTestHooks() {
    }

    public static void run(Stage stage) {
        String key = stage == Stage.CLIENT ? "siftec:selftest_client" : "siftec:selftest";
        for (Plugin plugin : FabricLoader.getInstance().getEntrypoints(key, Plugin.class)) plugin.register(stage);
    }
}
