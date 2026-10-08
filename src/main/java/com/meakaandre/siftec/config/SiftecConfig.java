package com.meakaandre.siftec.config;

import com.meakaandre.siftec.Siftec;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Server settings in config/siftec.properties. Read when the server starts; a missing file or key is written
 * with its default.
 */
public final class SiftecConfig {
    /** No claim may come within this many blocks of the world spawn (square, horizontal). 0 turns it off. */
    public static int spawnFreeRadius = 64;
    /**
     * Hours of real time a player who left (or was kicked from) a company still counts toward its cost multiplier,
     * and before they may join that company again.
     */
    public static int memberCooldownHours = 24;

    private SiftecConfig() {
    }

    public static long memberCooldownMillis() {
        return memberCooldownHours * 3_600_000L;
    }

    public static void load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("siftec.properties");
        Properties props = new Properties();
        try {
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    props.load(reader);
                }
            }
            spawnFreeRadius = read(props, "spawn_free_radius", 64);
            memberCooldownHours = read(props, "member_cooldown_hours", 24);
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                props.store(writer, "SIFTEC server settings. spawn_free_radius: blocks around the world spawn where nothing can be claimed (0 = off). "
                    + "member_cooldown_hours: how long a player who left a company still counts toward its costs and cannot rejoin it.");
            }
        } catch (Exception e) {
            Siftec.LOGGER.warn("SIFTEC: could not read or write {}; using defaults", file, e);
        }
        Siftec.LOGGER.info("SIFTEC settings: spawn_free_radius={}, member_cooldown_hours={}", spawnFreeRadius, memberCooldownHours);
    }

    private static int read(Properties props, String key, int fallback) {
        int value = fallback;
        try {
            String text = props.getProperty(key);
            if (text != null) value = Math.max(0, Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            Siftec.LOGGER.warn("SIFTEC: {} in siftec.properties is not a number; using {}", key, fallback);
        }
        props.setProperty(key, Integer.toString(value));
        return value;
    }
}
