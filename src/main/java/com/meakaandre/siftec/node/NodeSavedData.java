package com.meakaandre.siftec.node;

import com.meakaandre.siftec.Siftec;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;

/** Remembers which chunks already had their node blocks placed, so a node is never placed twice. */
public class NodeSavedData extends SavedData {
    public static final Codec<NodeSavedData> CODEC = Codec.LONG.listOf().xmap(
        list -> {
            NodeSavedData data = new NodeSavedData();
            data.done.addAll(list);
            return data;
        },
        data -> new ArrayList<>(data.done)
    );
    private static final SavedDataType<NodeSavedData> TYPE = new SavedDataType<>(
        Siftec.id("node_chunks"), NodeSavedData::new, CODEC, null
    );

    private final LongSet done = new LongOpenHashSet();

    public static NodeSavedData get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public boolean isDone(long chunk) {
        return done.contains(chunk);
    }

    public void markDone(long chunk) {
        if (done.add(chunk)) setDirty();
    }
}
