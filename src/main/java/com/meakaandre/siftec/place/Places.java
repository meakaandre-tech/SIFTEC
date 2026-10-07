package com.meakaandre.siftec.place;

import com.google.gson.Gson;
import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.registry.ModBlocks;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The places a company can send things or people to: its Drone Ports and its portals. Kept for the whole
 * world, so a port or portal can be picked from a list while its chunk is not loaded.
 */
public final class Places {
    public static final String DRONE_PORT = "drone_port", MAIN_PORTAL = "main_portal", SATELLITE_PORTAL = "satellite_portal";

    public static class Place {
        public String kind = "", company = "", name = "", dimension = "";
        public int x, y, z;

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }

        public boolean portal() {
            return !kind.equals(DRONE_PORT);
        }
    }

    public static class Data extends SavedData {
        private static final Gson GSON = new Gson();

        private static class Stored {
            Map<String, Place> places = new HashMap<>();
        }

        static final Codec<Data> CODEC = Codec.STRING.xmap(json -> {
            Data data = new Data();
            Stored stored = GSON.fromJson(json, Stored.class);
            if (stored != null) data.stored = stored;
            return data;
        }, data -> GSON.toJson(data.stored));
        static final SavedDataType<Data> TYPE = new SavedDataType<>(Siftec.id("places"), Data::new, CODEC, null);
        private Stored stored = new Stored();

        static Data get(MinecraftServer server) {
            return server.getDataStorage().computeIfAbsent(TYPE);
        }
    }

    private Places() {
    }

    private static String key(String dimension, BlockPos pos) {
        return dimension + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public static void add(ServerLevel level, BlockPos pos, String kind, String company, String name) {
        Place place = new Place();
        place.kind = kind;
        place.company = company;
        place.name = name;
        place.dimension = level.dimension().identifier().toString();
        place.x = pos.getX();
        place.y = pos.getY();
        place.z = pos.getZ();
        Data data = Data.get(level.getServer());
        data.stored.places.put(key(place.dimension, pos), place);
        data.setDirty();
    }

    public static ServerLevel level(MinecraftServer server, Place place) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(place.dimension)));
    }

    private static Block blockOf(String kind) {
        return kind.equals(DRONE_PORT) ? ModBlocks.DRONE_PORT.get() : kind.equals(MAIN_PORTAL) ? ModBlocks.MAIN_PORTAL.get() : ModBlocks.SATELLITE_PORTAL.get();
    }

    /** A company's places. One whose block has been broken drops off the list the first time its chunk is seen loaded. */
    /** Hands every port and portal of one company to another. */
    public static void reassign(MinecraftServer server, String from, String to) {
        Data data = Data.get(server);
        for (Place place : data.stored.places.values()) if (place.company.equals(from)) place.company = to;
        data.setDirty();
    }

    public static List<Place> of(MinecraftServer server, String company) {
        Data data = Data.get(server);
        List<Place> out = new ArrayList<>();
        for (Iterator<Place> it = data.stored.places.values().iterator(); it.hasNext(); ) {
            Place place = it.next();
            ServerLevel level = level(server, place);
            if (level == null || (level.isLoaded(place.pos()) && !level.getBlockState(place.pos()).is(blockOf(place.kind)))) {
                it.remove();
                data.setDirty();
                continue;
            }
            if (place.company.equals(company)) out.add(place);
        }
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    public static boolean same(Place place, Level level, BlockPos pos) {
        return place.dimension.equals(level.dimension().identifier().toString()) && place.pos().equals(pos);
    }
}
