package com.meakaandre.siftec.node;

import com.meakaandre.siftec.Siftec;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * What the node system has to remember about a world: the point the node map is measured from, and which
 * chunks already had their node blocks placed.
 */
public class NodeSavedData extends SavedData {
    public static final Codec<NodeSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.BOOL.optionalFieldOf("has_origin", false).forGetter(data -> data.hasOrigin),
        Codec.INT.optionalFieldOf("origin_x", 0).forGetter(data -> data.originX),
        Codec.INT.optionalFieldOf("origin_z", 0).forGetter(data -> data.originZ),
        Codec.LONG.listOf().optionalFieldOf("chunks", List.of()).forGetter(data -> new ArrayList<>(data.done))
    ).apply(instance, NodeSavedData::new));
    private static final SavedDataType<NodeSavedData> TYPE = new SavedDataType<>(
        Siftec.id("nodes"), NodeSavedData::new, CODEC, null
    );

    private boolean hasOrigin;
    private int originX, originZ;
    private final LongSet done = new LongOpenHashSet();

    public NodeSavedData() {
    }

    private NodeSavedData(boolean hasOrigin, int originX, int originZ, List<Long> chunks) {
        this.hasOrigin = hasOrigin;
        this.originX = originX;
        this.originZ = originZ;
        done.addAll(chunks);
    }

    public static NodeSavedData get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    /**
     * The world spawn as it was when the world was first played. The starter nodes and the purity falloff
     * are measured from here, and it never moves again, even if the spawn point is changed later.
     */
    public boolean hasOrigin() {
        return hasOrigin;
    }

    public int originX() {
        return originX;
    }

    public int originZ() {
        return originZ;
    }

    public void setOrigin(int x, int z) {
        hasOrigin = true;
        originX = x;
        originZ = z;
        setDirty();
    }

    public boolean isDone(long chunk) {
        return done.contains(chunk);
    }

    public void markDone(long chunk) {
        if (done.add(chunk)) setDirty();
    }
}
