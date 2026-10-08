package com.meakaandre.siftec.save;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.meakaandre.siftec.Siftec;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;

/**
 * World data kept as plain Java objects (so new fields never need a migration) but saved as a real NBT tree:
 * objects become compounds, arrays lists, numbers int/long/double tags, booleans byte tags. NBT strings are
 * capped at 64 KB, which the old one-JSON-string format overflowed on a busy server (the game then wrote an
 * empty string and everything was gone after a restart).
 * <p>
 * Loading reads the new tree and the old single-string format. If the file cannot be read at all, the data
 * is marked broken: a copy of the file is kept next to it, the error is logged loudly, and this data is never
 * written back, so the original file is not replaced by an empty one.
 */
public abstract class JsonSavedData<S> extends SavedData {
    /** Reads booleans written as bytes (NBT has no boolean), as well as real ones. */
    private static final TypeAdapter<Boolean> BOOL = new TypeAdapter<>() {
        @Override
        public void write(JsonWriter out, Boolean value) throws IOException {
            if (value == null) out.nullValue();
            else out.value(value);
        }

        @Override
        public Boolean read(JsonReader in) throws IOException {
            JsonToken token = in.peek();
            if (token == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            if (token == JsonToken.NUMBER) return in.nextDouble() != 0;
            if (token == JsonToken.STRING) return Boolean.parseBoolean(in.nextString());
            return in.nextBoolean();
        }
    };
    public static final Gson GSON = new GsonBuilder().registerTypeAdapter(Boolean.class, BOOL).registerTypeAdapter(boolean.class, BOOL).create();

    /** The server whose data is being loaded, so a broken file can be backed up. */
    private static volatile MinecraftServer server;

    public S stored;
    private boolean broken;
    private boolean warned;
    private final String file;
    private final Class<S> type;

    protected JsonSavedData(String file, Class<S> type, Supplier<S> empty) {
        this.file = file;
        this.type = type;
        this.stored = empty.get();
    }

    public static void serverStarting(MinecraftServer current) {
        server = current;
    }

    public static void serverStopped() {
        server = null;
    }

    /** True if the saved file could not be read; nothing is written back until the server restarts with it fixed. */
    public boolean broken() {
        return broken;
    }

    @Override
    public boolean isDirty() {
        if (broken) {
            if (super.isDirty() && !warned) {
                warned = true;
                Siftec.LOGGER.error("SIFTEC: NOT saving {} because the file could not be read when the world loaded. "
                    + "Fix or restore data/siftec/{}.dat (a copy is kept as {}.dat.broken-*) and restart; changes made now will be lost.", file, file, file);
            }
            return false;
        }
        return super.isDirty();
    }

    /** Called after loading, for indexes and fix-ups. */
    protected void loaded() {
    }

    public static <D extends JsonSavedData<?>> Codec<D> codec(Supplier<D> make) {
        return new Codec<>() {
            @Override
            public <T> DataResult<Pair<D, T>> decode(DynamicOps<T> ops, T input) {
                D data = make.get();
                ((JsonSavedData<?>) data).read(ops, input);
                return DataResult.success(Pair.of(data, ops.empty()));
            }

            @Override
            public <T> DataResult<T> encode(D data, DynamicOps<T> ops, T prefix) {
                Tag tag = toTag(GSON.toJsonTree(((JsonSavedData<?>) data).stored));
                return DataResult.success(NbtOps.INSTANCE.convertTo(ops, tag));
            }
        };
    }

    private <T> void read(DynamicOps<T> ops, T input) {
        try {
            Tag tag = input instanceof Tag t ? t : ops.convertTo(NbtOps.INSTANCE, input);
            JsonElement json;
            if (tag instanceof StringTag string) {
                // the old format: the whole thing as one JSON string
                json = JsonParser.parseString(string.value());
                Siftec.LOGGER.info("SIFTEC: {} was in the old one-string format ({} characters); it is saved as an NBT tree from now on", file, string.value().length());
            } else {
                json = toJson(tag);
            }
            S read = GSON.fromJson(json, type);
            if (read == null) throw new IllegalStateException("no data in the file");
            stored = read;
            loaded();
        } catch (Exception e) {
            broken = true;
            Siftec.LOGGER.error("SIFTEC: COULD NOT READ data/siftec/{}.dat. The data is kept on disk and will not be overwritten; "
                + "the game runs without it until the file is fixed.", file, e);
            backup();
        }
    }

    private void backup() {
        MinecraftServer current = server;
        if (current == null) return;
        try {
            Path path = current.getWorldPath(LevelResource.DATA).resolve("siftec").resolve(file + ".dat");
            if (Files.exists(path)) {
                Path copy = path.resolveSibling(file + ".dat.broken-" + System.currentTimeMillis());
                Files.copy(path, copy, StandardCopyOption.REPLACE_EXISTING);
                Siftec.LOGGER.error("SIFTEC: a copy of the unreadable file was kept as {}", copy);
            }
        } catch (Exception e) {
            Siftec.LOGGER.error("SIFTEC: could not copy the unreadable {}.dat", file, e);
        }
    }

    // JSON <-> NBT

    public static Tag toTag(JsonElement json) {
        if (json == null || json.isJsonNull()) return null;
        if (json.isJsonObject()) {
            CompoundTag tag = new CompoundTag();
            for (var e : json.getAsJsonObject().entrySet()) {
                Tag value = toTag(e.getValue());
                if (value != null) tag.put(e.getKey(), value);
            }
            return tag;
        }
        if (json.isJsonArray()) {
            ListTag list = new ListTag();
            for (JsonElement e : json.getAsJsonArray()) {
                Tag value = toTag(e);
                if (value != null) list.add(value);
            }
            return list;
        }
        JsonPrimitive p = json.getAsJsonPrimitive();
        if (p.isBoolean()) return ByteTag.valueOf(p.getAsBoolean());
        if (p.isString()) return StringTag.valueOf(p.getAsString());
        String text = p.getAsString();
        try {
            long value = Long.parseLong(text);
            return value == (int) value ? IntTag.valueOf((int) value) : LongTag.valueOf(value);
        } catch (NumberFormatException e) {
            return DoubleTag.valueOf(p.getAsDouble());
        }
    }

    public static JsonElement toJson(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            JsonObject out = new JsonObject();
            for (String key : compound.keySet()) out.add(key, toJson(compound.get(key)));
            return out;
        }
        if (tag instanceof ListTag list) {
            JsonArray out = new JsonArray();
            for (Tag e : list) out.add(toJson(e));
            return out;
        }
        if (tag instanceof StringTag string) return new JsonPrimitive(string.value());
        if (tag instanceof ByteTag b) return new JsonPrimitive(b.byteValue() != 0);
        if (tag instanceof IntTag || tag instanceof LongTag) return new JsonPrimitive(((NumericTag) tag).longValue());
        if (tag instanceof NumericTag n) return new JsonPrimitive(n.doubleValue());
        if (tag == null) return JsonNull.INSTANCE;
        throw new IllegalArgumentException("unexpected tag " + tag.getType());
    }
}
